package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.application.*;
import ru.taskflow.nlp.api.*;
import ru.taskflow.task.api.TaskService;

import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class ExperimentalAgentLoop {
    private final ContextBuilder contexts;
    private final PlanningPromptBuilder prompts;
    private final PlanningGenerationSettings generation;
    private final NlpGatewayService model;
    private final TaskService tasks;
    private final AgentLoop validator;
    private final CodeModeExecutor code;
    private final Clock clock;
    private final ObjectMapper json;

    public AgentOutcome run(PlanningMode mode, UUID user, String text, ZoneId zone, AssistantEntryPoint entry) {
        Instant start = clock.instant();
        TaskContextWindow window = contexts.build(user);
        PlanningSession session = new PlanningSession(tasks, user, json, window);
        String name = mode == PlanningMode.BATCH_PLAN ? "submit_plan" : "execute_code";
        var history = new ArrayList<LlmMessage>();
        history.add(LlmMessage.system(prompts.build(mode, session.preview(), tasks.findGroupNames(user), zone, entry)));
        history.add(LlmMessage.user(text));
        int inputTokens = 0, outputTokens = 0;
        long modelMs = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            Instant before = clock.instant();
            LlmToolResponse response = model.callWithTools(new LlmToolRequest(List.copyOf(history), List.of(definition(mode)), generation.reasoningEffort(attempt),
                    mode == PlanningMode.CODE_MODE ? name : null, mode == PlanningMode.BATCH_PLAN ? true : null));
            modelMs += Duration.between(before, clock.instant()).toMillis();
            inputTokens += response.inputTokens(); outputTokens += response.outputTokens();
            if (response.failed()) return failure("MODEL_UNAVAILABLE", "Ассистент сейчас недоступен. Попробуй позже или добавь задачу вручную.",
                    window, attempt + 1, inputTokens, outputTokens, start, modelMs);
            try {
                JsonNode args;
                if (mode == PlanningMode.BATCH_PLAN && response.text() != null && !response.text().isBlank())
                    args = json.readTree(response.text());
                else {
                    if (response.toolCalls() == null || response.toolCalls().size() != 1 || !name.equals(response.toolCalls().getFirst().name()))
                        throw new PlanningException("EXPECTED_SINGLE_TOOL");
                    args = json.readTree(response.toolCalls().getFirst().argumentsJson());
                }
                JsonNode result;
                if (mode == PlanningMode.BATCH_PLAN) result = session.expand(args);
                else {
                    PlanningSession.fields(args, Set.of("code"));
                    if (!args.path("code").isTextual()) throw new PlanningException("CODE_REQUIRED");
                    result = code.execute(args.get("code").asText(), OffsetDateTime.now(clock.withZone(zone)), session);
                }
                var calls = session.finish(result);
                AgentOutcome validated = validator.validatePlan(user, text,
                        new LlmToolResponse(calls, null, inputTokens, outputTokens, false), session.window(), start, modelMs);
                if (mode == PlanningMode.BATCH_PLAN && !validated.rejections().isEmpty())
                    throw new PlanningException("ACTION_VALIDATION", String.join("; ", validated.rejections()));
                return new AgentOutcome(validated.actions(), validated.rejections(), validated.clarification(),
                        validated.clarificationOptions(), validated.assistantText(), validated.window(), attempt + 1,
                        validated.llmFailed(), validated.ambiguous(), validated.ambiguityReason(), inputTokens, outputTokens,
                        Duration.between(start, clock.instant()).toMillis(), modelMs, 0, validated.declineReason());
            } catch (Exception e) {
                PlanningException issue = e instanceof PlanningException p ? p : new PlanningException("INVALID_PLAN");
                boolean repairable = Set.of("EXPECTED_SINGLE_TOOL", "INVALID_PLAN", "PROGRAM_ERROR", "TARGET_REQUIRED",
                        "SELECT_REQUIRED", "EXPECTED_OBJECT", "UNKNOWN_FIELD", "NOT_FOUND").contains(issue.getMessage())
                        || mode == PlanningMode.BATCH_PLAN && Set.of("ACTION_VALIDATION", "UNISSUED_REFERENCE").contains(issue.getMessage());
                if (attempt == 0 && repairable) {
                    if (mode == PlanningMode.BATCH_PLAN) session.beginRepair();
                    if (response.toolCalls() != null && response.toolCalls().size() == 1) {
                        history.add(LlmMessage.assistantToolCalls(response.toolCalls()));
                        history.add(LlmMessage.toolResult(response.toolCalls().getFirst().id(), name,
                                "План отклонён: " + issue.getMessage()
                                        + ". Исправь один раз; ничего не выполнено. "
                                        + "Для изменения существующей задачи нужен её ref из кандидатов или поиска. "
                                        + "remind без цели недопустим: новое дело с напоминанием — create с title и reminder_at. "
                                        + "У JSON select находится рядом с action, не внутри него. "
                                        + "api.now — строка ISO8601, не функция: используй new Date(api.now), не api.now(). "
                                        + "Верни объект с actions или decline/clarification."));
                    } else {
                        if (mode == PlanningMode.BATCH_PLAN && response.text() != null)
                            history.add(new LlmMessage("assistant", response.text(), null, null));
                        history.add(LlmMessage.user(mode == PlanningMode.BATCH_PLAN
                                ? "План целиком отклонён, ничего не выполнено. Верни один исправленный JSON-объект steps или clarification или decline. "
                                        + "Не удаляй правильные части исходной команды. Ошибка: " + issue.getMessage() + ". "
                                        + issue.question().substring(0, Math.min(1000, issue.question().length()))
                                : "Верни ровно один вызов " + name + " с корректными аргументами."));
                    }
                    continue;
                }
                return failure(issue.getMessage(), "ACTION_VALIDATION".equals(issue.getMessage())
                        ? "Не удалось подготовить все изменения. Уточни команду или попробуй ещё раз." : issue.question(), session.window(), attempt + 1,
                        inputTokens, outputTokens, start, modelMs);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private AgentOutcome failure(String reason, String question, TaskContextWindow window, int passes,
            int input, int output, Instant start, long modelMs) {
        return new AgentOutcome(List.of(), List.of(reason), question, List.of(), null, window, passes, false,
                false, null, input, output, Duration.between(start, clock.instant()).toMillis(), modelMs, 0);
    }

    public Map<String, Object> definition(PlanningMode mode) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("additionalProperties", false);
        if (mode == PlanningMode.CODE_MODE) {
            params.put("properties", Map.of("code", Map.of("type", "string", "maxLength", 16384)));
            params.put("required", List.of("code"));
        } else {
            Map<String, Object> actionProperties = new LinkedHashMap<>();
            actionProperties.put("type", Map.of("type", "string", "enum", List.of("create","complete","cancel","reschedule","update","remind")));
            for (String field : List.of("title","description","deadline","new_deadline","group","note","reason","reminder_at","ambiguous_reason"))
                actionProperties.put(field, Map.of("type", "string"));
            actionProperties.put("priority", Map.of("type", "string", "enum", List.of("LOW","MEDIUM","HIGH","URGENT")));
            actionProperties.put("tags", Map.of("type", "array", "items", Map.of("type", "string")));
            Map<String, Object> selectorProperties = new LinkedHashMap<>();
            for (String field : List.of("ref","query","group","status","priority","deadlineFrom","deadlineBefore"))
                selectorProperties.put(field, Map.of("type", "string"));
            for (String field : List.of("overdue","all")) selectorProperties.put(field, Map.of("type", "boolean"));
            var action = Map.of("type", "object", "additionalProperties", false, "properties", actionProperties, "required", List.of("type"));
            var selector = Map.of("type", "object", "additionalProperties", false, "properties", selectorProperties);
            params.put("properties", Map.of(
                    "steps", Map.of("type", "array", "minItems", 1, "maxItems", 20, "items",
                            Map.of("type", "object", "additionalProperties", false,
                                    "properties", Map.of("select", selector, "action", action), "required", List.of("action"))),
                    "clarification", Map.of("type", "string"),
                    "decline", Map.of("type", "object", "additionalProperties", false,
                            "properties", Map.of("reason", Map.of("type", "string", "enum", List.of("question","chitchat","unclear","past")),
                                    "answer", Map.of("type", "string")), "required", List.of("reason","answer"))));
        }
        return Map.of("type", "function", "function", Map.of("name",
                mode == PlanningMode.CODE_MODE ? "execute_code" : "submit_plan",
                "description", "Предложить действия над задачами без записи в базу", "parameters", params));
    }
}
