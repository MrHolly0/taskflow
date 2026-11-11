package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    public static final String PROPOSE_ACTIONS = "propose_actions";
    public static final String SEARCH_TASKS = "search_tasks";
    public static final String ASK_USER = "ask_user";
    public static final String NO_ACTION = "no_action";

    public boolean isProposeActions(String toolName) {
        return PROPOSE_ACTIONS.equals(toolName);
    }

    public boolean isRetrieval(String toolName) {
        return SEARCH_TASKS.equals(toolName);
    }

    public boolean isControl(String toolName) {
        return ASK_USER.equals(toolName);
    }

    public boolean isNoAction(String toolName) {
        return NO_ACTION.equals(toolName);
    }

    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
                tool(PROPOSE_ACTIONS, "Единственный инструмент для предложения действий над задачами. "
                        + "Вызывается один раз на ответ: все действия, которые следуют из реплики — "
                        + "новые задачи, закрытия, переносы срока, изменения полей, отмены — "
                        + "перечисляются элементами списка actions, а не отдельными вызовами, даже если "
                        + "действие всего одно — оно всё равно единственный элемент списка.", Map.of(
                        "actions", Map.of(
                                "type", "array",
                                "description", "Действия, по одному объекту на каждое",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.ofEntries(
                                                Map.entry("type", enumParam("Вид действия",
                                                        List.of("create", "complete", "reschedule", "update", "cancel", "remind"))),
                                                Map.entry("task_ref", stringParam(
                                                        "Ярлык существующей задачи из списка, например T3 — "
                                                                + "для всех видов, кроме create")),
                                                Map.entry("title", stringParam(
                                                        "Название задачи — обязательно для create, "
                                                                + "новое название для update")),
                                                Map.entry("description", stringParam(
                                                        "Подробности — для create и update")),
                                                Map.entry("priority", enumParam("Приоритет — для create и update",
                                                        List.of("LOW", "MEDIUM", "HIGH", "URGENT"))),
                                                Map.entry("deadline", stringParam(
                                                        "Срок в формате ISO-8601 со смещением, например "
                                                                + "2026-08-12T18:00:00+03:00 — для create")),
                                                Map.entry("new_deadline", stringParam(
                                                        "Новый срок в формате ISO-8601 со смещением — для reschedule")),
                                                Map.entry("group", stringParam(
                                                        "Название группы одним-двумя словами на русском — "
                                                                + "для create и update")),
                                                Map.entry("tags", arrayParam("Метки — для create")),
                                                Map.entry("note", stringParam(
                                                        "Короткий комментарий, если пользователь его дал — для complete")),
                                                Map.entry("reason", stringParam(
                                                        "Причина отмены, если пользователь её назвал — для cancel")),
                                                Map.entry("reminder_at", stringParam(
                                                        "Абсолютное время напоминания в формате ISO-8601 со "
                                                                + "смещением — для remind (обязательно) и опционально "
                                                                + "для create, если реплика создаёт задачу сразу с "
                                                                + "напоминанием. Срок задачи и время напоминания — "
                                                                + "разные вещи, remind не меняет deadline. Заполняй, "
                                                                + "только если время названо в реплике прямо — датой, "
                                                                + "временем или отступом от срока («за час до "
                                                                + "встречи»); не угадывай уместное время по смыслу "
                                                                + "задачи. Если названное время уже прошло — не "
                                                                + "заполняй это поле и не создавай задачу вместо "
                                                                + "него: вызови no_action с reason=past.")),
                                                Map.entry("reminder_ats",Map.of("type","array","minItems",1,"maxItems",5,
                                                        "items",Map.of("type","string"),"description","Несколько явно запрошенных напоминаний новой задачи в ISO8601. Один create, все времена в списке")),
                                                Map.entry("ambiguous_reason", stringParam(
                                                        "Заполняй, только если это действие — одно из "
                                                                + "взаимоисключающих прочтений одной и той же фразы "
                                                                + "вместе с другим действием из этого же списка actions: "
                                                                + "коротко, что именно неоднозначно, для показа "
                                                                + "пользователю"))
                                        ),
                                        "required", List.of("type")
                                )
                        )
                ), List.of("actions")),

                tool(SEARCH_TASKS, "Найти задачи пользователя, если нужной нет в показанном списке", Map.of(
                        "query", stringParam("Поисковая фраза"),
                        "include_completed", Map.of("type", "boolean", "description", "Искать среди выполненных тоже")
                ), List.of("query")),

                tool(NO_ACTION, "Используй вместо propose_actions, когда реплика не описывает действие "
                        + "над задачами: вопрос о данных, реплика без содержания или формулировка, из "
                        + "которой не восстанавливается ни одна команда. Короткая фраза, совпадающая по "
                        + "смыслу с активной задачей из списка, — не unclear: команда восстанавливается, "
                        + "просто неясно, о какой задаче речь, это двоякость через ambiguous_reason в "
                        + "propose_actions, а не отказ. Отдельная причина — past: реплика просит напомнить "
                        + "на время, которое уже прошло, — не создавай задачу вместо этого. Заменяет "
                        + "действие, а не дополняет его — одного вызова достаточно.", Map.of(
                        "reason", enumParam("Почему действие не предлагается",
                                List.of("question", "chitchat", "unclear", "past")),
                        "answer", stringParam("Короткий ответ пользователю")
                ), List.of("reason", "answer"))
        );
    }

    private Map<String, Object> tool(String name, String description,
                                     Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", required
                        )
                )
        );
    }

    private Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> enumParam(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    private Map<String, Object> arrayParam(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }
}
