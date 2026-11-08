package ru.taskflow.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.nlp.api.LlmMessage;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.TaskResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Собирает реплику пользователя в проверенный набор предложенных действий:
 * окно контекста → промпт → вызов модели → разбор → защита от дублей,
 * с возможным вторым проходом, если модель попросила поиск. В базу ничего
 * не пишет — сохранение предложений появится в следующей части.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentLoop implements AssistantPlanner {

    private static final Duration BUDGET = Duration.ofSeconds(35);
    private static final String SEARCH_REF_PREFIX = "T";
    private static final int MAX_FALLBACK_TITLE_LENGTH = 512;
    private static final Pattern WORDS = Pattern.compile("\\s+");

    private final ContextBuilder contextBuilder;
    private final AssistantPromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final NlpGatewayService nlpGatewayService;
    private final ToolCallParser toolCallParser;
    private final DuplicateGuard duplicateGuard;
    private final TitleChangeGuard titleChangeGuard;
    private final TaskService taskService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgentOutcome run(UUID userId, String userText, ZoneId zone) {
        return run(userId, userText, zone, AssistantEntryPoint.CHAT);
    }

    public AgentOutcome run(UUID userId, String userText, ZoneId zone, AssistantEntryPoint entryPoint) {
        Instant start = clock.instant();

        TaskContextWindow window = contextBuilder.build(userId);
        List<String> groupNames = taskService.findGroupNames(userId);
        PromptParts prompt = promptBuilder.build(window, userText, zone, entryPoint, groupNames);
        List<Map<String, Object>> tools = toolRegistry.toolDefinitions();

        List<LlmMessage> historyPass1 = List.of(
                LlmMessage.system(prompt.systemPrompt()),
                LlmMessage.user(prompt.userMessage())
        );
        Instant pass1Start = clock.instant();
        LlmToolResponse response1 = nlpGatewayService.callWithTools(new LlmToolRequest(historyPass1, tools));
        long firstPassLatencyMs = Duration.between(pass1Start, clock.instant()).toMillis();

        AgentOutcome outcome = runFirstPass(userId, userText, historyPass1, tools, response1, window, start);
        return withLatencies(outcome, Duration.between(start, clock.instant()).toMillis(), firstPassLatencyMs);
    }

    public AgentOutcome validatePlan(UUID userId, String text, LlmToolResponse response,
            TaskContextWindow window, Instant start, long modelLatencyMs) {
        if (response.toolCalls().stream().anyMatch(call -> ToolRegistry.SEARCH_TASKS.equals(call.name())))
            throw new IllegalArgumentException("Поиск должен завершиться до проверки плана");
        AgentOutcome result = runFirstPass(userId, text, List.of(), List.of(), response, window, start);
        return withLatencies(result, Duration.between(start, clock.instant()).toMillis(), modelLatencyMs);
    }

    private AgentOutcome runFirstPass(UUID userId, String userText, List<LlmMessage> historyPass1,
                                       List<Map<String, Object>> tools, LlmToolResponse response1,
                                       TaskContextWindow window, Instant start) {
        // разомкнутый автомат / исчерпаны попытки — ничего не спасаем из этого вызова,
        // реплику сохранит вызывающая сторона отдельным путём деградации (часть 2в)
        if (response1.failed()) {
            return new AgentOutcome(List.of(), List.of(), null, null, null, window, 1, true);
        }

        ParsedToolCalls parsed1 = toolCallParser.parse(toDomainCalls(response1.toolCalls()), window);
        DuplicateGuard.GuardResult guarded1 = duplicateGuard.filter(parsed1.actions(), window);
        TitleChangeGuard.GuardResult titled1 = titleChangeGuard.filter(guarded1.actions(), window, userText);

        List<String> rejections = new ArrayList<>(parsed1.rejections());
        rejections.addAll(guarded1.rejections());
        rejections.addAll(titled1.rejections());

        // no_action заменяет действие, а не дополняет его (Б1) — до всех
        // остальных веток: клaрификация/двоякость приходят полем действия
        // внутри propose_actions и с отказом не пересекаются.
        if (parsed1.isDeclined()) {
            return new AgentOutcome(List.of(), rejections, null, null, parsed1.declineAnswer(), window, 1, false,
                    false, null, response1.inputTokens(), response1.outputTokens(), 0, 0, 0, parsed1.declineReason());
        }

        if (parsed1.isClarification() || parsed1.ambiguous()) {
            return new AgentOutcome(titled1.actions(), rejections, parsed1.clarification(),
                    parsed1.clarificationOptions(), response1.text(), window, 1, false,
                    parsed1.ambiguous(), parsed1.ambiguityReason(), response1.inputTokens(), response1.outputTokens());
        }

        if (!parsed1.needsSecondPass() || budgetExceeded(start)) {
            return finishWithFallback(userText, titled1.actions(), rejections, response1.text(), window, 1,
                    parsed1.rejectedTarget(), response1.inputTokens(), response1.outputTokens());
        }

        return runSecondPass(userId, userText, historyPass1, tools, response1, parsed1, titled1.actions(), window, rejections);
    }

    private AgentOutcome runSecondPass(UUID userId, String userText, List<LlmMessage> historyPass1, List<Map<String, Object>> tools,
                                        LlmToolResponse response1, ParsedToolCalls parsed1,
                                        List<ProposedAction> pass1Actions, TaskContextWindow window,
                                        List<String> rejections) {
        LlmToolCall searchCall = findSearchCall(response1.toolCalls());
        List<TaskResponse> found = taskService.search(userId, parsed1.searchQuery(), false, 20);
        ExtendedWindow extended = extendWindow(window, found);

        List<LlmMessage> historyPass2 = new ArrayList<>(historyPass1);
        historyPass2.add(LlmMessage.assistantToolCalls(List.of(searchCall)));
        historyPass2.add(LlmMessage.toolResult(
                searchCall.id(), ToolRegistry.SEARCH_TASKS, serializeFoundTasks(extended.newRefs(), found)));

        Instant pass2Start = clock.instant();
        LlmToolResponse response2 = nlpGatewayService.callWithTools(new LlmToolRequest(historyPass2, tools));
        long secondPassLatencyMs = Duration.between(pass2Start, clock.instant()).toMillis();

        // модель уже вернула проверенные действия первого прохода — провал ОБОГАЩАЮЩЕГО
        // второго вызова не должен стирать то, что уже честно получено (принцип
        // «сказанное не теряется» из спеки); llmFailed=false, потому что первый вызов
        // реально удался, отказал только необязательный довесок
        if (response2.failed()) {
            AgentOutcome outcome = new AgentOutcome(pass1Actions, rejections, null, null,
                    response1.text(), extended.window(), 2, false, false, null,
                    response1.inputTokens(), response1.outputTokens());
            return withSecondPassLatency(outcome, secondPassLatencyMs);
        }

        ParsedToolCalls parsed2 = toolCallParser.parse(toDomainCalls(response2.toolCalls()), extended.window());
        DuplicateGuard.GuardResult guarded2 = duplicateGuard.filter(parsed2.actions(), extended.window(), pass1Actions);
        TitleChangeGuard.GuardResult titled2 = titleChangeGuard.filter(guarded2.actions(), extended.window(), userText);

        rejections.addAll(parsed2.rejections());
        rejections.addAll(guarded2.rejections());
        rejections.addAll(titled2.rejections());

        if (parsed2.isDeclined()) {
            AgentOutcome outcome = new AgentOutcome(List.of(), rejections, null, null, parsed2.declineAnswer(),
                    extended.window(), 2, false, false, null,
                    response1.inputTokens() + response2.inputTokens(),
                    response1.outputTokens() + response2.outputTokens(),
                    0, 0, 0, parsed2.declineReason());
            return withSecondPassLatency(outcome, secondPassLatencyMs);
        }

        List<ProposedAction> combined = combineAndRenumber(pass1Actions, titled2.actions());

        String clarification = parsed2.isClarification() ? parsed2.clarification() : null;
        List<String> clarificationOptions = parsed2.isClarification() ? parsed2.clarificationOptions() : null;
        String assistantText = isBlank(response2.text()) ? response1.text() : response2.text();

        int inputTokens = response1.inputTokens() + response2.inputTokens();
        int outputTokens = response1.outputTokens() + response2.outputTokens();

        if (clarification != null || parsed2.ambiguous()) {
            AgentOutcome outcome = new AgentOutcome(combined, rejections, clarification, clarificationOptions,
                    assistantText, extended.window(), 2, false, parsed2.ambiguous(), parsed2.ambiguityReason(),
                    inputTokens, outputTokens);
            return withSecondPassLatency(outcome, secondPassLatencyMs);
        }

        UUID rejectedTarget = parsed2.rejectedTarget() != null ? parsed2.rejectedTarget() : parsed1.rejectedTarget();
        AgentOutcome outcome = finishWithFallback(userText, combined, rejections, assistantText, extended.window(), 2,
                rejectedTarget, inputTokens, outputTokens);
        return withSecondPassLatency(outcome, secondPassLatencyMs);
    }

    /**
     * Задержки проставляются поверх результата, а не протаскиваются параметрами через
     * finishWithFallback/runSecondPass — тех и так уже девять параметров ради токенов,
     * ещё одна пара сделала бы сигнатуры нечитаемыми. totalLatencyMs выставляет только
     * run() (это время всего вызова), firstPassLatencyMs — тоже только run() (второй
     * проход не знает, сколько длился первый); secondPassLatencyMs выставляет только
     * runSecondPass(), потому что только он видит длительность своего вызова модели.
     */
    private AgentOutcome withLatencies(AgentOutcome outcome, long totalLatencyMs, long firstPassLatencyMs) {
        return new AgentOutcome(outcome.actions(), outcome.rejections(), outcome.clarification(),
                outcome.clarificationOptions(), outcome.assistantText(), outcome.window(), outcome.passes(),
                outcome.llmFailed(), outcome.ambiguous(), outcome.ambiguityReason(), outcome.inputTokens(),
                outcome.outputTokens(), totalLatencyMs, firstPassLatencyMs, outcome.secondPassLatencyMs(),
                outcome.declineReason());
    }

    private AgentOutcome withSecondPassLatency(AgentOutcome outcome, long secondPassLatencyMs) {
        return new AgentOutcome(outcome.actions(), outcome.rejections(), outcome.clarification(),
                outcome.clarificationOptions(), outcome.assistantText(), outcome.window(), outcome.passes(),
                outcome.llmFailed(), outcome.ambiguous(), outcome.ambiguityReason(), outcome.inputTokens(),
                outcome.outputTokens(), outcome.totalLatencyMs(), outcome.firstPassLatencyMs(), secondPassLatencyMs,
                outcome.declineReason());
    }

    /**
     * Двоякость — не особый случай, а результат разбора: реплика читается как
     * название задачи (структура) и при этом либо модель промолчала, либо
     * предложила действие над задачей, с которой реплика не совпадает почти
     * дословно (мера DuplicateGuard, его порог). Совпадает — это дубль, им уже
     * занимается DuplicateGuard; не совпадает — оба прочтения правдоподобны.
     */
    private AgentOutcome finishWithFallback(String userText, List<ProposedAction> actions, List<String> rejections,
                                            String assistantText, TaskContextWindow window, int passes,
                                            UUID rejectedTarget, int inputTokens, int outputTokens) {
        // Пустой actions() бывает по четырём причинам: модель ничего не
        // предложила, предложила — но фильтры отбросили (Task 0 части 3б,
        // чужое решение не подменяем), ярлык разрешился, а дальше
        // ActionValidator отклонил остальное (нечего менять, не разобрался
        // срок — rejectedTarget не null, это не «фильтр отбросил
        // осмысленное», а то же самое молчание с понятной задачей), или
        // модель сходила в поиск и не нашла что предложить (passes == 2,
        // действий нет, отказов нет — это запрос к данным, а не название
        // новой задачи: «покажи задачи на завтра» точно так же не читалась
        // бы отдельно от контекста поиска, если бы поиска не было).
        boolean searchedAndFoundNothingToPropose = passes == 2 && actions.isEmpty() && rejections.isEmpty();
        boolean modelSaidNothing = actions.isEmpty() && (rejections.isEmpty() || rejectedTarget != null)
                && !searchedAndFoundNothingToPropose;
        boolean readsLikeATaskName = looksLikeStandaloneTask(userText);

        if (modelSaidNothing && readsLikeATaskName) {
            DuplicateGuard.GuardResult guarded = duplicateGuard.filter(List.of(fallbackCreateAction(userText)), window);
            List<String> allRejections = new ArrayList<>(rejections);
            allRejections.addAll(guarded.rejections());
            return new AgentOutcome(guarded.actions(), allRejections, null, null, assistantText, window, passes, false,
                    false, null, inputTokens, outputTokens);
        }

        // complete_task сюда не попадает по той же логике, что и раньше:
        // «закрыть X» и «создать X» — не два правдоподобных прочтения одной
        // реплики. reschedule/update сюда тоже не попадают — живой прогон
        // (эксперимент Б, UE-01..UE-04, «перенеси встречу с директором на
        // завтра на 15») показал: ActionValidator не пропускает reschedule
        // без нового срока и update без изменённого поля — если действие
        // этого типа вообще дошло досюда как единственное, оно уже несёт
        // значение, извлечённое из самой реплики (новый срок, новый
        // приоритет/группа/название). Явная команда с параметром двоякой не
        // бывает: «перенеси X на завтра на 15» нельзя прочесть как название
        // новой задачи, в отличие от голой ссылки без параметров («закрыть
        // кино», «отчёт») — там ветка остаётся как была, это и чинила
        // «изменить планы на кино». remind (блок В) по той же причине, что
        // reschedule/update: reminder_at обязателен по ActionValidator и
        // всегда извлечён из самой реплики.
        if (readsLikeATaskName && actions.size() == 1 && actions.getFirst().targetTaskId() != null
                && actions.getFirst().type() != AssistantActionType.COMPLETE
                && actions.getFirst().type() != AssistantActionType.RESCHEDULE
                && actions.getFirst().type() != AssistantActionType.UPDATE
                && actions.getFirst().type() != AssistantActionType.REMIND) {
            ProposedAction existing = actions.getFirst();
            String targetTitle = window.titleFor(existing.targetTaskId());
            if (!duplicateGuard.isDuplicateOf(userText, targetTitle)) {
                List<ProposedAction> alternatives = orderAlternatives(existing, fallbackCreateAction(userText));
                String reason = "реплика могла означать «" + targetTitle + "», а могла — новую задачу";
                return new AgentOutcome(alternatives, rejections, null, null, assistantText, window, passes, false,
                        true, reason, inputTokens, outputTokens);
            }
        }

        return new AgentOutcome(actions, rejections, null, null, assistantText, window, passes, false,
                false, null, inputTokens, outputTokens);
    }

    /**
     * Предложение модели — всегда первым и выбранным по умолчанию, независимо
     * от точки входа: мы дополняем его альтернативой, а не спорим с ним.
     * «Создание первым для быстрого добавления» — правило для другой ветки
     * (modelSaidNothing выше), где вариант создания синтезировали мы сами,
     * потому что модель ничего не предложила; здесь она уже высказалась.
     */
    private List<ProposedAction> orderAlternatives(ProposedAction existing, ProposedAction create) {
        List<ProposedAction> ordered = List.of(existing, create);
        List<ProposedAction> renumbered = new ArrayList<>(2);
        for (int i = 0; i < ordered.size(); i++) {
            ProposedAction a = ordered.get(i);
            renumbered.add(new ProposedAction(i + 1, a.type(), a.targetTaskId(), a.payload(), a.summary(), i == 0));
        }
        return renumbered;
    }

    private ProposedAction fallbackCreateAction(String userText) {
        String title = normalizeTitle(userText);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        return new ProposedAction(1, AssistantActionType.CREATE, null, payload,
                truncate("Создать — " + title), true);
    }

    // Только структура — длина, число слов, не вопрос. Ни одного глагола:
    // открытый класс форм и синонимов такой список всё равно не покрыл бы
    // (см. историю в плане Task 5, вторая редакция).
    private boolean looksLikeStandaloneTask(String userText) {
        String title = normalizeTitle(userText);
        if (title.length() < 8 || title.length() > MAX_FALLBACK_TITLE_LENGTH || title.endsWith("?")) {
            return false;
        }

        String[] words = WORDS.split(title);
        return words.length >= 2;
    }

    private String normalizeTitle(String userText) {
        return WORDS.matcher(userText.trim()).replaceAll(" ");
    }

    private String truncate(String value) {
        return value.length() <= 256 ? value : value.substring(0, 255) + "…";
    }

    private LlmToolCall findSearchCall(List<LlmToolCall> calls) {
        return calls.stream()
                .filter(call -> ToolRegistry.SEARCH_TASKS.equals(call.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "needsSecondPass() истинно без исходного вызова " + ToolRegistry.SEARCH_TASKS));
    }

    private boolean budgetExceeded(Instant start) {
        return Duration.between(start, clock.instant()).compareTo(BUDGET) >= 0;
    }

    private List<ToolCall> toDomainCalls(List<LlmToolCall> calls) {
        return calls.stream()
                .map(call -> new ToolCall(call.id(), call.name(), call.argumentsJson()))
                .toList();
    }

    private List<ProposedAction> combineAndRenumber(List<ProposedAction> firstPass, List<ProposedAction> secondPass) {
        List<ProposedAction> combined = new ArrayList<>(firstPass.size() + secondPass.size());
        combined.addAll(firstPass);
        combined.addAll(secondPass);

        List<ProposedAction> renumbered = new ArrayList<>(combined.size());
        int ordinal = 1;
        for (ProposedAction action : combined) {
            renumbered.add(new ProposedAction(ordinal++, action.type(), action.targetTaskId(),
                    action.payload(), action.summary(), action.accepted()));
        }
        return renumbered;
    }

    private record ExtendedWindow(TaskContextWindow window, List<String> newRefs) {}

    private ExtendedWindow extendWindow(TaskContextWindow original, List<TaskResponse> found) {
        Map<String, UUID> refs = new LinkedHashMap<>(original.refs());
        Map<String, String> titles = new LinkedHashMap<>(original.titles());
        StringBuilder rendered = new StringBuilder(original.rendered());
        List<String> newRefs = new ArrayList<>(found.size());

        int nextIndex = original.size() + 1;
        for (TaskResponse task : found) {
            String ref = SEARCH_REF_PREFIX + nextIndex++;
            refs.put(ref, task.id());
            titles.put(ref, task.title());
            if (!rendered.isEmpty()) {
                rendered.append('\n');
            }
            rendered.append(ref).append(" · ").append(task.title());
            newRefs.add(ref);
        }

        return new ExtendedWindow(new TaskContextWindow(rendered.toString(), Map.copyOf(refs), Map.copyOf(titles)), newRefs);
    }

    private String serializeFoundTasks(List<String> refs, List<TaskResponse> found) {
        List<Map<String, String>> payload = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            payload.add(Map.of("ref", refs.get(i), "title", found.get(i).title()));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Не удалось сериализовать результаты поиска, вернём пустой список", e);
            return "[]";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
