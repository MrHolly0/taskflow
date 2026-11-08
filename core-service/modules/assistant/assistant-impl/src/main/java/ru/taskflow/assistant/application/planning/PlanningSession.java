package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.taskflow.assistant.application.TaskContextWindow;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.AssistantTaskSelector;
import ru.taskflow.task.api.dto.TaskResponse;

import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

public class PlanningSession {
    private static final Set<String> SELECTOR_FIELDS = Set.of("ref", "query", "group", "status", "priority", "overdue", "deadlineFrom", "deadlineBefore", "all");
    private static final Set<String> ACTION_FIELDS = Set.of("type", "task_ref", "title", "description", "priority", "deadline", "new_deadline", "group", "tags", "note", "reason", "reminder_at", "ambiguous_reason");
    private static final String DEADLINE_TIME = "new_deadline_time";
    private static final String BEFORE_DEADLINE = "reminder_before_deadline_minutes";
    private static final Set<String> JSON_ACTION_FIELDS;
    static {
        var fields = new HashSet<>(ACTION_FIELDS);fields.add(BEFORE_DEADLINE);fields.add(DEADLINE_TIME);
        JSON_ACTION_FIELDS = Set.copyOf(fields);
    }
    private final TaskService tasks;
    private final UUID userId;
    private final ObjectMapper json;
    private final TaskContextWindow initial;
    private final ZoneId zone;
    private final String source;
    private final Map<String, UUID> refs;
    private final Map<String, String> titles;
    private final Set<String> issued = new HashSet<>();
    private List<Map<String, Object>> preview;
    private final Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
    private int calls;
    private PlanningException failure;

    public PlanningSession(TaskService tasks, UUID userId, ObjectMapper json, TaskContextWindow initial, ZoneId zone, String source) {
        this.tasks = tasks; this.userId = userId; this.json = json; this.initial = initial;this.zone = zone;this.source = source;
        refs = new LinkedHashMap<>(initial.refs()); titles = new LinkedHashMap<>(initial.titles());
    }

    public List<Map<String, Object>> preview() {
        if (preview == null) preview = tasks.findAssistantContext(userId, 12).stream().map(this::expose).toList();
        return preview;
    }

    public List<Map<String, Object>> select(JsonNode selector) {
        try {
            fields(selector, SELECTOR_FIELDS);
            if (selector.has("ref")) {
                fields(selector, Set.of("ref"));
                if (!selector.get("ref").isTextual() || !issued.contains(selector.get("ref").asText()))
                    throw new PlanningException("UNISSUED_REFERENCE");
                return List.of(snapshots.get(selector.get("ref").asText()));
            }
            if (++calls > 8) throw new PlanningException("TOOL_LIMIT");
            for (String name : List.of("all", "overdue"))
                if (selector.has(name) && !selector.get(name).isBoolean()) throw new PlanningException("INVALID_SELECTOR");
            for (String name : List.of("query", "group", "status", "priority", "deadlineFrom", "deadlineBefore"))
                if (selector.has(name) && !selector.get(name).isTextual()) throw new PlanningException("INVALID_SELECTOR");
            var request = json.treeToValue(selector, AssistantTaskSelector.class);
            var result = tasks.selectForAssistant(userId, request);
            if (result.truncated()) throw new PlanningException("SELECTION_LIMIT", "Найдено больше 20 задач. Уточни группу, период или название.");
            if (result.tasks().size() > 1 && !request.all()) throw new PlanningException("AMBIGUOUS", "Подходят несколько задач. Уточни название или явно укажи, что нужно изменить все найденные.");
            return result.tasks().stream().map(this::expose).toList();
        } catch (PlanningException e) { failure = e; throw e; }
        catch (Exception e) { failure = new PlanningException("INVALID_SELECTOR"); throw failure; }
    }

    private Map<String, Object> expose(TaskResponse task) {
        String ref = refs.entrySet().stream().filter(entry -> entry.getValue().equals(task.id())).map(Map.Entry::getKey)
                .findFirst().orElseGet(() -> "T" + (refs.size() + 1));
        refs.put(ref, task.id()); titles.put(ref, task.title()); issued.add(ref);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ref", ref); result.put("title", task.title()); result.put("status", task.status().name());
        result.put("priority", task.priority().name());
        if (task.groupName() != null) result.put("group", task.groupName());
        if (task.deadline() != null) result.put("deadline", task.deadline().toString());
        if (task.plannedDate() != null) result.put("plannedDate", task.plannedDate().toString());
        if (task.estimateMinutes() != null) result.put("estimateMinutes", task.estimateMinutes());
        snapshots.put(ref, Map.copyOf(result));
        return result;
    }

    public JsonNode expand(JsonNode plan) {
        fields(plan, Set.of("steps", "alternatives", "reason", "clarification", "decline"));
        if (plan.has("alternatives")) {
            if (plan.has("steps") || plan.hasNonNull("clarification") || plan.hasNonNull("decline"))
                throw new PlanningException("MIXED_CONTROL");
            var alternatives = plan.path("alternatives");
            String reason = plan.path("reason").asText("Существующее или новое дело");
            if (!alternatives.isArray() || alternatives.size() != 2 || reason.isBlank() || reason.length() > 500
                    || "create".equals(alternatives.get(0).path("action").path("type").asText())
                    == "create".equals(alternatives.get(1).path("action").path("type").asText()))
                throw new PlanningException("INVALID_ACTION", "alternatives содержит ровно два шага: один create и одно действие над существующей задачей.");
            var steps = alternatives.deepCopy();
            for (JsonNode step : steps) {
                fields(step, Set.of("select", "action"));
                if (!step.path("action").isObject()) throw new PlanningException("EXPECTED_OBJECT");
                ((com.fasterxml.jackson.databind.node.ObjectNode) step.get("action")).put("ambiguous_reason", reason);
            }
            return expand(json.createObjectNode().set("steps", steps));
        }
        if (plan.has("reason")) throw new PlanningException("INVALID_ACTION", "reason верхнего уровня используется только с alternatives.");
        if (plan.hasNonNull("clarification") || plan.hasNonNull("decline")) {
            if (plan.path("steps").size() != 0) throw new PlanningException("MIXED_CONTROL");
            var control = json.createObjectNode();
            if (plan.hasNonNull("clarification")) control.set("clarification", plan.get("clarification"));
            if (plan.hasNonNull("decline")) control.set("decline", plan.get("decline"));
            return control;
        }
        JsonNode steps = plan.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > 20) throw new PlanningException("STEP_LIMIT");
        var actions = json.createArrayNode();
        for (JsonNode step : steps) {
            fields(step, Set.of("select", "action"));
            JsonNode action = step.get("action");
            validateJsonAction(action);
            if (action.has("task_ref")) throw new PlanningException("SELECT_REQUIRED");
            if ("create".equals(action.path("type").asText())) {
                if (step.has("select")) throw new PlanningException("CREATE_WITH_SELECTOR");
                actions.add(resolveReminder(action, null));
            } else {
                if (!step.has("select")) throw new PlanningException("SELECT_REQUIRED");
                var selected = select(step.get("select"));
                if (selected.isEmpty()) throw new PlanningException("NOT_FOUND", "Подходящая задача не найдена. Уточни её название.");
                for (var task : selected) {
                    var expanded = resolveReminder(action, task);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) expanded).put("task_ref", (String) task.get("ref"));
                    actions.add(expanded);
                    if ("reschedule".equals(expanded.path("type").asText()) && expanded.has("reminder_at")) {
                        var reminder = json.createObjectNode().put("type", "remind").put("task_ref", (String) task.get("ref"));
                        reminder.set("reminder_at", expanded.remove("reminder_at"));
                        actions.add(reminder);
                    }
                }
            }
            if (actions.size() > 20) throw new PlanningException("ACTION_LIMIT");
        }
        validateRequestedValues(actions);
        boolean ambiguous = false;
        for (JsonNode action : actions) if (!action.path("ambiguous_reason").asText().isBlank()) ambiguous = true;
        if (ambiguous && (actions.size() != 2
                || actions.get(0).path("ambiguous_reason").asText().isBlank()
                || !actions.get(0).path("ambiguous_reason").asText().equals(actions.get(1).path("ambiguous_reason").asText())
                || "create".equals(actions.get(0).path("type").asText()) == "create".equals(actions.get(1).path("type").asText())))
            throw new PlanningException("INVALID_ACTION", "ambiguous_reason допустим только в двух взаимоисключающих вариантах: "
                    + "один create и одно действие над существующей задачей, с одинаковой причиной в обоих. "
                    + "Отсутствие задачи среди кандидатов не является двоякостью. Для обычной команды убери ambiguous_reason.");
        return json.createObjectNode().set("actions", actions);
    }

    private void validateRequestedValues(JsonNode actions) {
        String text = source.toLowerCase(Locale.ROOT).replaceAll("«[^»]*»|“[^”]*”|\"[^\"]*\"", " ");
        var priorityActions = new ArrayList<JsonNode>();var reschedules = new ArrayList<JsonNode>();
        for (JsonNode action : actions) {
            if (action.hasNonNull("priority")) priorityActions.add(action);
            if ("reschedule".equals(action.path("type").asText())) reschedules.add(action);
        }
        boolean namesPriority = text.contains("приоритет") || text.strip().startsWith("срочно ")
                || text.matches("(?su).*(сделай|назначь|установи|выставь|поставь)\\s+(срочн|высок|низк|обычн|средн|нормальн).*" );
        if (priorityActions.size() == 1 && namesPriority && !text.matches("(?sU).*\\bне\\s+(срочн|высок|низк|обычн|средн|нормальн).*")) {
            var priorities = new HashSet<String>();
            for (String word : text.split("[^\\p{L}]+")) {
                if (word.startsWith("срочн")) priorities.add("URGENT");
                else if (word.startsWith("высок") || word.startsWith("повышенн")) priorities.add("HIGH");
                else if (word.startsWith("низк") || word.startsWith("понижен")) priorities.add("LOW");
                else if (word.startsWith("обычн") || word.startsWith("средн") || word.startsWith("нормальн")) priorities.add("MEDIUM");
            }
            if (priorities.size() == 1 && !priorities.iterator().next().equalsIgnoreCase(priorityActions.getFirst().path("priority").asText()))
                throw new PlanningException("INVALID_ACTION", "Единственный явно названный приоритет в реплике: " + priorities.iterator().next()
                        + ". Исправь priority, сохрани остальные действия. Срочный=URGENT, высокий=HIGH — разные значения.");
        }
        if (reschedules.size() != 1 || text.matches("(?su).*(числ|январ|феврал|март|апрел|мая|июн|июл|август|сентябр|октябр|ноябр|декабр).*")) return;
        var matcher = java.util.regex.Pattern.compile("(?U)(?:перенеси|перенести|сдвинь|сдвинуть|передвинь)[^,;!?\\n]{0,120}?\\bна\\s+([01]?[0-9]|2[0-3])(?::([0-5][0-9]))?\\b(?![-0-9:])").matcher(text);
        if (!matcher.find()) return;
        int hour = Integer.parseInt(matcher.group(1)), minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        if (matcher.find()) return;
        var action = reschedules.getFirst();var selected = snapshots.get(action.path("task_ref").asText());
        try {
            var deadline = OffsetDateTime.parse(action.path("new_deadline").asText()).atZoneSameInstant(zone);
            boolean namedDay = text.matches("(?su).*(сегодня|завтра|послезавтра|через|понедельник|вторник|среду|четверг|пятниц|суббот|воскресен).*" );
            boolean wrongDay = !namedDay && selected != null && selected.get("deadline") != null
                    && !deadline.toLocalDate().equals(OffsetDateTime.parse(selected.get("deadline").toString()).atZoneSameInstant(zone).toLocalDate());
            if (deadline.getHour() != hour || deadline.getMinute() != minute || wrongDay)
                throw new PlanningException("INVALID_ACTION", "В реплике «на " + String.format(Locale.ROOT,"%02d:%02d",hour,minute)
                        + "» — время, не число месяца. Без нового дня используй new_deadline_time, сервер сохранит день задачи.");
        } catch (java.time.format.DateTimeParseException ignored) {
            // Формат абсолютного срока проверит общий валидатор.
        }
    }

    private void validateJsonAction(JsonNode action) {
        fields(action, JSON_ACTION_FIELDS);
        String type = action.path("type").asText();
        Set<String> mutable = switch (type) {
            case "create" -> Set.of("title", "description", "priority", "deadline", "group", "tags", "reminder_at", BEFORE_DEADLINE);
            case "update" -> Set.of("title", "description", "priority", "group");
            case "reschedule" -> Set.of("new_deadline", DEADLINE_TIME, "reminder_at", BEFORE_DEADLINE);
            case "remind" -> Set.of("reminder_at", BEFORE_DEADLINE);
            case "complete", "cancel" -> Set.of();
            default -> throw new PlanningException("INVALID_ACTION", "action.type: create, complete, cancel, reschedule, update или remind.");
        };
        var allowed = new HashSet<>(mutable);
        allowed.addAll(Set.of("type", "task_ref", "note", "reason", "ambiguous_reason"));
        action.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new PlanningException("INVALID_ACTION",
                    "Поле " + field + " не относится к " + type + ". Для переноса срока используй reschedule с new_deadline; "
                            + "update меняет только title, description, priority или group. Напоминание: remind с reminder_at "
                            + "либо reminder_before_deadline_minutes. Сохрани все остальные части исходной команды.");
        });
        if (action.has(DEADLINE_TIME) && (!action.get(DEADLINE_TIME).isTextual()
                || !action.get(DEADLINE_TIME).asText().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]") || action.has("new_deadline")))
            throw new PlanningException("INVALID_ACTION", "Для переноса только времени укажи new_deadline_time в HH:mm БЕЗ new_deadline.");
        if ("create".equals(type) && Set.of("купить", "сделать", "позвонить", "добавить", "создать", "перенести", "удалить", "отменить", "напомнить")
                    .contains(source.strip().toLowerCase(Locale.ROOT)))
            throw new PlanningException("INVALID_ACTION", "В исходной реплике только глагол без объекта. Нельзя создавать пустое дело или придумывать объект. Верни decline unclear или clarification.");
        if (action.has(BEFORE_DEADLINE)) {
            JsonNode offset = action.get(BEFORE_DEADLINE);
            if (!offset.isIntegralNumber() || !offset.canConvertToInt() || offset.intValue() < 0 || offset.intValue() > 525600)
                throw new PlanningException("INVALID_ACTION", "reminder_before_deadline_minutes — целое число от 0 до 525600.");
            if (action.has("reminder_at")) throw new PlanningException("INVALID_ACTION",
                    "Укажи только один способ напоминания: reminder_at ИЛИ reminder_before_deadline_minutes.");
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode resolveReminder(JsonNode action, Map<String, Object> task) {
        var expanded = (com.fasterxml.jackson.databind.node.ObjectNode) action.deepCopy();
        if (action.has(DEADLINE_TIME)) {
            if (task == null || task.get("deadline") == null)
                throw new PlanningException("DEADLINE_REQUIRED", "У задачи нет срока. На какой день перенести время?");
            var date = OffsetDateTime.parse(task.get("deadline").toString()).atZoneSameInstant(zone).toLocalDate();
            var local = date.atTime(LocalTime.parse(action.get(DEADLINE_TIME).asText()));
            if (zone.getRules().getValidOffsets(local).size() != 1)
                throw new PlanningException("TIMEZONE_TRANSITION", "Это время попадает на смену часового пояса. Уточни момент со смещением UTC.");
            expanded.remove(DEADLINE_TIME);expanded.put("new_deadline", local.atZone(zone).toOffsetDateTime().toString());
        }
        if (!action.has(BEFORE_DEADLINE)) return expanded;
        Object deadline = "create".equals(action.path("type").asText()) ? action.path("deadline").asText(null)
                : "reschedule".equals(action.path("type").asText()) ? expanded.path("new_deadline").asText(null) : task.get("deadline");
        if (deadline == null) throw new PlanningException("DEADLINE_REQUIRED", "У задачи нет срока. Когда напомнить или к какому сроку привязать напоминание?");
        try {
            var reminder = OffsetDateTime.parse(deadline.toString()).minusMinutes(action.get(BEFORE_DEADLINE).intValue());
            expanded.remove(BEFORE_DEADLINE);expanded.put("reminder_at", reminder.toString());
            return expanded;
        } catch (RuntimeException e) {
            throw new PlanningException("INVALID_ACTION", "Для вычисления напоминания нужен полный deadline/new_deadline в ISO8601 со смещением зоны.");
        }
    }

    public List<LlmToolCall> finish(JsonNode result) {
        if (failure != null) throw failure;
        fields(result, Set.of("actions", "clarification", "decline"));
        boolean clarification = result.hasNonNull("clarification");
        boolean decline = result.hasNonNull("decline");
        JsonNode actions = result.path("actions");
        if ((clarification || decline) && actions.size() > 0 || clarification && decline) throw new PlanningException("MIXED_CONTROL");
        if (clarification) {
            if (!result.get("clarification").isTextual() || result.get("clarification").asText().isBlank()
                    || result.get("clarification").asText().length() > 500) throw new PlanningException("INVALID_QUESTION");
            return List.of(new LlmToolCall("plan", "ask_user", json.createObjectNode().set("question", result.get("clarification")).toString()));
        }
        if (decline) {
            fields(result.get("decline"), Set.of("reason", "answer"));
            if (!Set.of("question", "chitchat", "unclear", "past").contains(result.get("decline").path("reason").asText())
                    || !result.get("decline").path("answer").isTextual()
                    || result.get("decline").path("answer").asText().isBlank()
                    || result.get("decline").path("answer").asText().length() > 500) throw new PlanningException("INVALID_DECLINE");
            return List.of(new LlmToolCall("plan", "no_action", result.get("decline").toString()));
        }
        if (!actions.isArray() || actions.isEmpty() || actions.size() > 20) throw new PlanningException("ACTION_LIMIT");
        for (JsonNode action : actions) {
            fields(action, ACTION_FIELDS);
            if (!"create".equals(action.path("type").asText())) {
                if (action.path("task_ref").asText().isBlank()) throw new PlanningException("TARGET_REQUIRED");
                if (!issued.contains(action.path("task_ref").asText())) throw new PlanningException("UNISSUED_REFERENCE");
            }
        }
        return List.of(new LlmToolCall("plan", "propose_actions", result.toString()));
    }

    public TaskContextWindow window() { return new TaskContextWindow(initial.rendered(), Map.copyOf(refs), Map.copyOf(titles)); }
    public int calls() { return calls; }
    void beginRepair() { failure = null; }
    static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new PlanningException("EXPECTED_OBJECT");
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new PlanningException("UNKNOWN_FIELD", "Недопустимое поле " + name + ". На этом уровне разрешены: " + new TreeSet<>(allowed) + ". ambiguous_reason относится к action, не к step."); });
    }
}
