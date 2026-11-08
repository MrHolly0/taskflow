package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.taskflow.assistant.application.TaskContextWindow;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.AssistantTaskSelector;
import ru.taskflow.task.api.dto.TaskResponse;

import java.util.*;

public class PlanningSession {
    private static final Set<String> SELECTOR_FIELDS = Set.of("ref", "query", "group", "status", "priority", "overdue", "deadlineFrom", "deadlineBefore", "all");
    private static final Set<String> ACTION_FIELDS = Set.of("type", "task_ref", "title", "description", "priority", "deadline", "new_deadline", "group", "tags", "note", "reason", "reminder_at", "ambiguous_reason");
    private final TaskService tasks;
    private final UUID userId;
    private final ObjectMapper json;
    private final TaskContextWindow initial;
    private final Map<String, UUID> refs;
    private final Map<String, String> titles;
    private final Set<String> issued = new HashSet<>();
    private List<Map<String, Object>> preview;
    private final Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
    private int calls;
    private PlanningException failure;

    public PlanningSession(TaskService tasks, UUID userId, ObjectMapper json, TaskContextWindow initial) {
        this.tasks = tasks; this.userId = userId; this.json = json; this.initial = initial;
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
        fields(plan, Set.of("steps", "clarification", "decline"));
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
            fields(action, ACTION_FIELDS);
            if (action.has("task_ref")) throw new PlanningException("SELECT_REQUIRED");
            if ("create".equals(action.path("type").asText())) {
                if (step.has("select")) throw new PlanningException("CREATE_WITH_SELECTOR");
                actions.add(action);
            } else {
                if (!step.has("select")) throw new PlanningException("SELECT_REQUIRED");
                var selected = select(step.get("select"));
                if (selected.isEmpty()) throw new PlanningException("NOT_FOUND", "Подходящая задача не найдена. Уточни её название.");
                for (var task : selected) {
                    var expanded = action.deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) expanded).put("task_ref", (String) task.get("ref"));
                    actions.add(expanded);
                }
            }
            if (actions.size() > 20) throw new PlanningException("ACTION_LIMIT");
        }
        return json.createObjectNode().set("actions", actions);
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
    static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new PlanningException("EXPECTED_OBJECT");
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new PlanningException("UNKNOWN_FIELD"); });
    }
}
