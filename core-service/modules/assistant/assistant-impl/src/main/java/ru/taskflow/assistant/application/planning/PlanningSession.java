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
    private static final Set<String> ACTION_FIELDS = Set.of("type", "task_ref", "title", "description", "priority", "deadline", "planned_date", "new_deadline", "group", "tags", "note", "reason", "reminder_at", "reminder_ats", "ambiguous_reason");
    private static final String DEADLINE_TIME = "new_deadline_time";
    private static final String AFTER_NOW = "reminder_after_minutes";
    private static final String BEFORE_DEADLINE = "reminder_before_deadline_minutes";
    private static final Set<String> JSON_ACTION_FIELDS;
    static {
        var fields = new HashSet<>(ACTION_FIELDS);fields.add(BEFORE_DEADLINE);fields.add(DEADLINE_TIME);fields.add(AFTER_NOW);
        JSON_ACTION_FIELDS = Set.copyOf(fields);
    }
    private final TaskService tasks;
    private final UUID userId;
    private final ObjectMapper json;
    private final TaskContextWindow initial;
    private final ZoneId zone;
    private final OffsetDateTime now;
    private final String source;
    private final boolean preferCreate;
    private final boolean smartSearch;
    private List<TaskResponse> pendingSuggestions = List.of();
    private boolean choiceResolved;
    private Map<String,Object> choiceCandidate;
    private final Map<String, UUID> refs;
    private final Map<String, String> titles;
    private final Set<String> issued = new HashSet<>();
    private List<Map<String, Object>> preview;
    private final Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
    private int calls;
    private PlanningException failure;

    public PlanningSession(TaskService tasks, UUID userId, ObjectMapper json, TaskContextWindow initial, ZoneId zone, String source, boolean preferCreate, boolean smartSearch, OffsetDateTime now) {
        this.tasks = tasks; this.userId = userId; this.json = json; this.initial = initial;this.zone = zone;this.source = source;this.preferCreate = preferCreate;this.smartSearch = smartSearch;this.now = now;
        refs = new LinkedHashMap<>(initial.refs()); titles = new LinkedHashMap<>(initial.titles());
    }

    public List<Map<String, Object>> preview() {
        if (preview == null) {
            var initialTasks = tasks.findAssistantContext(userId,12);
            var ordered = new LinkedHashMap<UUID,TaskResponse>();
            if (smartSearch && initialTasks.size() == 12) {
                var words = new StringBuilder();
                for (String word : source.split("[^\\p{L}\\p{N}]+")) {
                    if (word.length() < 3) continue;
                    if (words.length()+word.length()+1 > 200) break;
                    if (!words.isEmpty()) words.append(' ');words.append(word);
                }
                if (!words.isEmpty()) {
                    var resolution = tasks.resolveForAssistant(userId,new AssistantTaskSelector(words.toString(),null,null,null,false,null,null,false));
                    var relevant = resolution.selection().tasks().isEmpty() ? resolution.suggestions() : resolution.selection().tasks();
                    relevant.stream().limit(6).forEach(t -> ordered.put(t.id(),t));
                }
            }
            initialTasks.forEach(t -> ordered.putIfAbsent(t.id(),t));
            preview = ordered.values().stream().limit(12).map(this::expose).toList();
        }
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
            ru.taskflow.task.api.dto.AssistantTaskSelection result;
            if (smartSearch) {
                var resolution = tasks.resolveForAssistant(userId,request);
                result = resolution.selection();pendingSuggestions = resolution.suggestions();
            } else result = tasks.selectForAssistant(userId,request);
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
        String instruction = sourceWithoutQuotes();
        if (instruction.matches("(?s).*(создай|добавь|запиши).*с названием.*"))
            instruction = instruction.substring(0, instruction.indexOf("с названием"));
        if (instruction.matches("(?s).*(игнорируй(?:те)?|забудь(?:те)?|отмени(?:те)?)\\s+.{0,50}(инструкци\\p{L}*|системн\\p{L}* правила).*" )
                || instruction.matches("(?s).*(ignore|disregard|forget|override)\\s+.{0,50}(instructions|system rules).*" )
                || instruction.matches("(?s).*(?:^|\\n)\\s*(system|developer)\\s*:.*")) {
            return json.createObjectNode().set("decline",json.createObjectNode().put("reason","unclear")
                    .put("answer","Напиши, какую задачу нужно добавить или изменить."));
        }
        var userRefs = java.util.regex.Pattern.compile("(?U)\\b(?:перенеси|отмени|удали|заверши|закрой|отметь|передвинь|сдвинь)\\s+(?:задачу\\s+)?(t[0-9]+)\\b").matcher(instruction);
        while (userRefs.find()) {
            String ref = userRefs.group(1).toUpperCase(Locale.ROOT);
            if (!issued.contains(ref)) return json.createObjectNode().put("clarification",
                    "Метка " + ref + " не относится к текущим задачам. Назови задачу полностью.");
        }
        fields(plan, Set.of("steps", "alternatives", "reason", "clarification", "decline"));
        if ("past".equals(plan.path("decline").path("reason").asText()) && floatingReminderTime() != null)
            throw new PlanningException("INVALID_ACTION", "Час без дня означает ближайшее будущее наступление этого часа. Это не просьба напомнить в прошлом; используй reminder_at в ближайший подходящий день.");
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
            var candidate = contextualCandidate();
            for (JsonNode step : steps) {
                fields(step, Set.of("select", "action"));
                if (!step.path("action").isObject()) throw new PlanningException("EXPECTED_OBJECT");
                if (candidate != null && Set.of("complete","cancel").contains(step.path("action").path("type").asText())) {
                    if (step.path("select").has("ref") && !candidate.get("ref").equals(step.path("select").path("ref").asText()))
                        throw new PlanningException("INVALID_ACTION", "Выбранная задача не соответствует короткому названию пользователя.");
                    ((com.fasterxml.jackson.databind.node.ObjectNode)step).set("select",json.createObjectNode().put("ref",(String)candidate.get("ref")));
                }
                ((com.fasterxml.jackson.databind.node.ObjectNode) step.get("action")).put("ambiguous_reason", reason);
            }
            return expand(json.createObjectNode().set("steps", steps));
        }
        if (plan.has("reason")) throw new PlanningException("INVALID_ACTION", "reason верхнего уровня используется только с alternatives.");
        if ("past".equals(plan.path("decline").path("reason").asText()) && !requestsReminder())
            throw new PlanningException("INVALID_ACTION", "Прошедший deadline допустим; past предназначен для прошедших напоминаний. "
                    + "Явное новое событие с датой создавай с deadline. Сообщение о выполнении сопоставь с существующей задачей. "
                    + "Если это разговор без задачи, используй decline chitchat.");
        if (plan.hasNonNull("clarification") || plan.hasNonNull("decline")) {
            if (plan.path("steps").size() != 0) throw new PlanningException("MIXED_CONTROL");
            var control = json.createObjectNode();
            if (plan.hasNonNull("clarification")) control.set("clarification", plan.get("clarification"));
            if (plan.hasNonNull("decline")) control.set("decline", plan.get("decline"));
            return control;
        }
        JsonNode steps = plan.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > 20) throw new PlanningException("STEP_LIMIT");
        var calendarFields = new ArrayList<String>();
        for (JsonNode step : steps) {
            var action=step.path("action");
            if (!action.path("ambiguous_reason").asText().isBlank()) { calendarFields.add("ambiguous");calendarFields.add("ambiguous"); }
            for (String field : List.of("deadline","new_deadline","reminder_at",DEADLINE_TIME))
                if(action.hasNonNull(field))calendarFields.add(field);
            if(action.path("reminder_ats").isArray()&&!action.path("reminder_ats").isEmpty())calendarFields.add("reminder_ats");
        }
        var day = calendarFields.size() == 1 ? RelativeDayPolicy.uniqueOffset(sourceWithoutQuotes()) : OptionalInt.empty();
        var weekday=steps.size()==1&&calendarFields.size()==1&&Set.of("deadline","new_deadline",DEADLINE_TIME).contains(calendarFields.getFirst())
                &&!steps.get(0).path("action").hasNonNull("planned_date")?
                WeekdayPolicy.nextWeek(sourceWithoutQuotes(),now.atZoneSameInstant(zone).toLocalDate()):Optional.<java.time.LocalDate>empty();
        if(steps.size()==1&&calendarFields.size()>1&&!steps.get(0).path("action").hasNonNull("planned_date")) {
            var expected=WeekdayPolicy.nextWeek(sourceWithoutQuotes(),now.atZoneSameInstant(zone).toLocalDate());
            var raw=steps.get(0).path("action");
            for(String field:List.of("deadline","new_deadline")) if(expected.isPresent()&&raw.hasNonNull(field)) {
                try {
                    if(!OffsetDateTime.parse(raw.path(field).asText()).atZoneSameInstant(zone).toLocalDate().equals(expected.get()))
                        throw new PlanningException("INVALID_ACTION","Названный день следующей календарной недели — "+expected.get()+". Исправь срок и связанные напоминания. Для «за день до этого» используй reminder_before_deadline_minutes:1440 вместо самостоятельного расчёта даты.");
                } catch(java.time.format.DateTimeParseException ignored) { }
            }
        }
        var actions = json.createArrayNode();
        for (JsonNode step : steps) {
            fields(step, Set.of("select", "action"));
            JsonNode action = step.get("action");
            validateJsonAction(action);
            if (day.isPresent()) action = resolveCalendarDay(action,calendarFields.getFirst(),day.getAsInt());
            if(weekday.isPresent()) action=resolveCalendarDay(action,calendarFields.getFirst(),(int)java.time.temporal.ChronoUnit.DAYS.between(now.atZoneSameInstant(zone).toLocalDate(),weekday.get()));
            if(steps.size()==1) CreateDetailsPolicy.validate(action,sourceWithoutQuotes());
            if (action.has("task_ref")) throw new PlanningException("SELECT_REQUIRED");
            if ("create".equals(action.path("type").asText())) {
                if (step.has("select")) throw new PlanningException("CREATE_WITH_SELECTOR","У create не должно быть select: это новая задача. Убери select, сохрани все параметры нового дела.");
                actions.add(resolveReminder(action, null));
            } else {
                if (!step.has("select")) throw new PlanningException("SELECT_REQUIRED");
                var selected = select(step.get("select"));
                if (selected.isEmpty()) throw new PlanningException("NOT_FOUND", "Подходящая задача не найдена. Уточни её название.");
                if (selected.size() > 1 && !java.util.regex.Pattern.compile("(?U)\\b(вс[её]х?|все[мх]?|всю|весь|кажд\\p{L}*|об[ае]|обоих)\\b")
                        .matcher(sourceWithoutQuotes()).find())
                    throw new PlanningException("AMBIGUOUS","Найдено несколько задач. Уточни одну или явно попроси изменить все найденные.");
                for (var task : selected) {
                    var expanded = resolveReminder(action, task);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) expanded).put("task_ref", (String) task.get("ref"));
                    actions.add(expanded);
                }
            }
            if (actions.size() > 20) throw new PlanningException("ACTION_LIMIT");
        }
        var reminderVerb=java.util.regex.Pattern.compile("(?U)\\bнапомн\\p{L}*\\b").matcher(sourceWithoutQuotes());
        int reminderVerbs=0;while(reminderVerb.find()) reminderVerbs++;
        if(reminderVerbs>=2 && sourceWithoutQuotes().matches("(?sU).*(дополнительно|также|ещ[её])\\s+напомн.*")) {
            int supplied=0;
            for(var action:actions) supplied+=(action.hasNonNull("reminder_at")?1:0)+(action.hasNonNull(AFTER_NOW)?1:0)
                    +(action.hasNonNull(BEFORE_DEADLINE)?1:0)+action.path("reminder_ats").size();
            if(supplied<2) throw new PlanningException("INVALID_ACTION","В реплике запрошено дополнительное напоминание. Сохрани оба времени в одном create, не теряй второе.");
        }
        actions = compileSchedule(actions);
        if (!requestsReminder()) for (JsonNode action : actions)
            if (action.hasNonNull("reminder_at") || action.hasNonNull("reminder_ats")) throw new PlanningException("INVALID_ACTION",
                    "Пользователь не просил уведомление. Время без просьбы напомнить относится к deadline, не reminder_at. Сохрани все действия.");
        if (actions.size() == 1 && "create".equals(actions.get(0).path("type").asText())
                && !actions.get(0).hasNonNull("deadline") && !actions.get(0).hasNonNull("planned_date") && !requestsReminder()
                && !sourceWithoutQuotes().contains("с названием")
                && !sourceWithoutQuotes().matches("(?s).*(без\\s+(срок\\p{L}*|дедлайн\\p{L}*)|не\\s+(ставь|указывай|назначай)\\s+(срок|дедлайн)).*")
                && java.util.regex.Pattern.compile("(?U)\\b(сегодня|завтр[ао]|послезавтра)\\b").matcher(sourceWithoutQuotes()).find())
            throw new PlanningException("INVALID_ACTION", "В реплике явно назван день, но у create пропущен deadline. "
                    + "Исправь дату из исходной реплики, включая ошибки диктовки. При отсутствии часа используй конец дня, 23:59, даже если этот deadline уже прошёл.");
        actions = contextualChoice(actions);
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

    private Map<String,Object> contextualCandidate() {
        if (choiceResolved) return choiceCandidate;
        choiceResolved = true;
        if (requestsReminder()) return null;
        String topic = ContextualChoicePolicy.topic(source);
        if (topic == null) return null;
        List<Map<String,Object>> found;
        if (preview().size() < 12) {
            found = preview().stream().filter(t -> ContextualChoicePolicy.matches(topic,(String)t.get("title"))).toList();
        } else {
            var matches = new LinkedHashMap<String,Map<String,Object>>();
            for (var task : select(json.createObjectNode().put("query",topic).put("all",true)))
                if (ContextualChoicePolicy.matches(topic,(String)task.get("title"))) matches.put((String)task.get("ref"),task);
            if (matches.size() > 1) throw new PlanningException("AMBIGUOUS","Подходят несколько задач. Уточни полное название.");
            String other = topic.contains("ё") ? topic.replace('ё','е') : topic.contains("е") ? topic.replace('е','ё') : topic;
            if (!other.equals(topic)) for (var task : select(json.createObjectNode().put("query",other).put("all",true)))
                if (ContextualChoicePolicy.matches(topic,(String)task.get("title"))) matches.put((String)task.get("ref"),task);
            found = List.copyOf(matches.values());
        }
        if (found.size() > 1) throw new PlanningException("AMBIGUOUS","Подходят несколько задач. Уточни полное название.");
        if (found.size() == 1 && !ContextualChoicePolicy.sameTitle(source,(String)found.getFirst().get("title"))) choiceCandidate = found.getFirst();
        return choiceCandidate;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode contextualChoice(com.fasterxml.jackson.databind.node.ArrayNode actions) {
        if (actions.size() != 1 || !Set.of("create","complete","cancel").contains(actions.get(0).path("type").asText())) return actions;
        var candidate = contextualCandidate();if (candidate == null) return actions;
        var proposed = actions.get(0);
        if (!"create".equals(proposed.path("type").asText()) && !candidate.get("ref").equals(proposed.path("task_ref").asText()))
            throw new PlanningException("INVALID_ACTION","Выбранная задача не соответствует короткому названию пользователя.");
        String type = "create".equals(proposed.path("type").asText()) ? ContextualChoicePolicy.cancel(source) ? "cancel" : "complete" : proposed.path("type").asText();
        String reason = "Существующее или новое дело";
        var existing = json.createObjectNode().put("type",type).put("task_ref",(String)candidate.get("ref")).put("ambiguous_reason",reason);
        var created = json.createObjectNode().put("type","create").put("title",source.strip()).put("ambiguous_reason",reason);
        var result = json.createArrayNode();
        if (preferCreate) {result.add(created);result.add(existing);} else {result.add(existing);result.add(created);}
        return result;
    }

    private String sourceWithoutQuotes() {
        return source.toLowerCase(Locale.ROOT).replaceAll("«[^»]*»|“[^”]*”|\"[^\"]*\"", " ");
    }

    private boolean requestsReminder() {
        return sourceWithoutQuotes().matches("(?sU).*(нап[оа]мн|нап[оа]мин|уведом|оповест|оповещ|маякни|пинган|сигнализируй|(?:поставь|сделай|дай|пришли)\\s+(?:мне\\s+)?сигнал\\b|не\\s+дай\\s+забыть).*" );
    }

    private void validateRequestedValues(JsonNode actions) {
        String text = sourceWithoutQuotes();
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
            case "create" -> Set.of("title", "description", "priority", "deadline", "planned_date", "group", "tags", "reminder_at", "reminder_ats", BEFORE_DEADLINE, AFTER_NOW);
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
        if ("create".equals(type) && (Set.of("купить", "сделать", "позвонить", "добавить", "создать", "перенести", "удалить", "отменить", "напомнить")
                    .contains(source.strip().toLowerCase(Locale.ROOT))
                    || source.strip().toLowerCase(Locale.ROOT).matches("(добавь|создай|запиши)\\s+(мне\\s+)?(задачу|дело)[.!?]?")))
            throw new PlanningException("INVALID_ACTION", "В исходной реплике только глагол без объекта. Нельзя создавать пустое дело или придумывать объект. Верни decline unclear или clarification.");
        if(action.has(AFTER_NOW)) {
            var offset=action.get(AFTER_NOW);
            if(!offset.isIntegralNumber() || !offset.canConvertToInt() || offset.intValue()<1 || offset.intValue()>525600)
                throw new PlanningException("INVALID_ACTION","reminder_after_minutes — целое число от 1 до 525600.");
        }
        if(action.has("reminder_ats") && (!action.get("reminder_ats").isArray() || action.get("reminder_ats").isEmpty()
                || action.get("reminder_ats").size()>5)) throw new PlanningException("INVALID_ACTION","reminder_ats — от одного до пяти времён ISO8601.");
        if (action.has(BEFORE_DEADLINE)) {
            JsonNode offset = action.get(BEFORE_DEADLINE);
            if (!offset.isIntegralNumber() || !offset.canConvertToInt() || offset.intValue() < 0 || offset.intValue() > 525600)
                throw new PlanningException("INVALID_ACTION", "reminder_before_deadline_minutes — целое число от 0 до 525600.");
            if (action.has("reminder_at") && !"create".equals(type)) throw new PlanningException("INVALID_ACTION",
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
        return expanded;
    }

    private JsonNode resolveCalendarDay(JsonNode raw,String field,int days) {
        if (!raw.hasNonNull(field)) return raw;
        if ("reminder_at".equals(field) && java.util.regex.Pattern.compile("(?U)\\bза\\b").matcher(sourceWithoutQuotes()).find()) return raw;
        try {
            var time=DEADLINE_TIME.equals(field) ? LocalTime.parse(raw.path(field).asText())
                    : OffsetDateTime.parse(raw.path(field).asText()).atZoneSameInstant(zone).toLocalTime();
            var expectedDay=now.atZoneSameInstant(zone).toLocalDate().plusDays(days);
            if (!DEADLINE_TIME.equals(field) && OffsetDateTime.parse(raw.path(field).asText()).atZoneSameInstant(zone).toLocalDate().equals(expectedDay)) return raw;
            var local=expectedDay.atTime(time);
            if(zone.getRules().getValidOffsets(local).size()!=1)
                throw new PlanningException("TIMEZONE_TRANSITION","Это время попадает на смену часового пояса. Уточни день и смещение UTC.");
            var action=(com.fasterxml.jackson.databind.node.ObjectNode)raw.deepCopy();
            if (DEADLINE_TIME.equals(field)) action.remove(DEADLINE_TIME);
            action.put(DEADLINE_TIME.equals(field) ? "new_deadline" : field,local.atZone(zone).toOffsetDateTime().toString());return action;
        } catch(java.time.format.DateTimeParseException e) { return raw; }
    }

    private LocalTime floatingReminderTime() {
        return requestsReminder() ? FloatingReminderTimePolicy.from(sourceWithoutQuotes()) : null;
    }

    private void normalizeFloatingReminder(com.fasterxml.jackson.databind.node.ObjectNode action) {
        if (!action.hasNonNull("reminder_at")) return;
        var time = floatingReminderTime();if (time == null) return;
        var current = now.atZoneSameInstant(zone);
        var local = current.toLocalDate().atTime(time);
        if (!local.atZone(zone).toInstant().isAfter(now.toInstant())) local = local.plusDays(1);
        if (zone.getRules().getValidOffsets(local).size() != 1)
            throw new PlanningException("TIMEZONE_TRANSITION","Это время попадает на смену часового пояса. Уточни день и смещение UTC.");
        action.put("reminder_at",local.atZone(zone).toOffsetDateTime().toString());
    }

    private com.fasterxml.jackson.databind.node.ArrayNode compileSchedule(com.fasterxml.jackson.databind.node.ArrayNode actions) {
        var deadlines = new HashMap<String,String>();var terminal = new HashMap<String,String>();
        for (JsonNode action : actions) {
            String ref = action.path("task_ref").asText();
            String type = action.path("type").asText();
            if ("reschedule".equals(type) && action.hasNonNull("new_deadline")) {
                String previous = deadlines.put(ref,action.get("new_deadline").asText());
                if (previous != null && !previous.equals(action.get("new_deadline").asText()))
                    throw new PlanningException("CONFLICTING_ACTIONS","Для одной задачи указаны разные сроки. Какой оставить?");
            }
            if (Set.of("complete","cancel").contains(type)) {
                String previous = terminal.put(ref,type);
                if (previous != null && !previous.equals(type)) throw new PlanningException("CONFLICTING_ACTIONS","Задачу завершить или отменить?");
            }
        }
        long absoluteReminders = java.util.stream.StreamSupport.stream(actions.spliterator(),false)
                .filter(a -> a.hasNonNull("reminder_at")).count();
        var result = new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        for (JsonNode raw : actions) {
            var action = (com.fasterxml.jackson.databind.node.ObjectNode)raw;
            String type = action.path("type").asText(),ref = action.path("task_ref").asText();
            if (("remind".equals(type) || "reschedule".equals(type)) && terminal.containsKey(ref))
                throw new PlanningException("CONFLICTING_ACTIONS","Нельзя одновременно закрыть задачу и назначить ей новый срок или напоминание. Уточни команду.");
            boolean compiledReminders=false;
            if("create".equals(type) && (action.has(AFTER_NOW) || action.has("reminder_ats")
                    || (action.has("reminder_at") && action.has(BEFORE_DEADLINE)))) {
                compiledReminders=true;
                var times=new java.util.TreeSet<java.time.Instant>();
                try {
                    if(action.has("reminder_at")) times.add(OffsetDateTime.parse(action.get("reminder_at").asText()).toInstant());
                    if(action.has("reminder_ats")) for(var at:action.get("reminder_ats")) {
                        if(!at.isTextual()) throw new IllegalArgumentException();
                        times.add(OffsetDateTime.parse(at.asText()).toInstant());
                    }
                    if(action.has(AFTER_NOW)) times.add(now.plusMinutes(action.get(AFTER_NOW).intValue()).toInstant());
                    if(action.has(BEFORE_DEADLINE)) {
                        if(!action.hasNonNull("deadline")) throw new PlanningException("DEADLINE_REQUIRED","Для отступа от срока новой задачи нужен deadline.");
                        times.add(OffsetDateTime.parse(action.get("deadline").asText()).minusMinutes(action.get(BEFORE_DEADLINE).intValue()).toInstant());
                    }
                } catch(java.time.format.DateTimeParseException invalid) { throw new PlanningException("INVALID_ACTION","Времена напоминаний должны содержать дату и смещение UTC."); }
                catch(IllegalArgumentException invalid) { throw new PlanningException("INVALID_ACTION","reminder_ats содержит только времена ISO8601."); }
                if(times.isEmpty() || times.size()>5) throw new PlanningException("INVALID_ACTION","Нужно от одного до пяти разных напоминаний.");
                action.remove(List.of("reminder_at","reminder_ats",AFTER_NOW,BEFORE_DEADLINE));
                if(times.size()==1) action.put("reminder_at",times.first().atZone(zone).toOffsetDateTime().toString());
                else {var values=json.createArrayNode();times.forEach(at->values.add(at.atZone(zone).toOffsetDateTime().toString()));action.set("reminder_ats",values);}
            }
            if (action.has(BEFORE_DEADLINE)) {
                Object basis = "create".equals(type) ? action.path("deadline").asText(null)
                        : "reschedule".equals(type) ? action.path("new_deadline").asText(null)
                        : deadlines.containsKey(ref) ? deadlines.get(ref) : snapshots.get(ref).get("deadline");
                if (basis == null) throw new PlanningException("DEADLINE_REQUIRED","У задачи нет срока. Когда напомнить или к какому сроку привязать напоминание?");
                try {
                    String at = OffsetDateTime.parse(basis.toString()).minusMinutes(action.get(BEFORE_DEADLINE).intValue()).toString();
                    action.remove(BEFORE_DEADLINE);action.put("reminder_at",at);
                } catch (java.time.format.DateTimeParseException e) {
                    throw new PlanningException("INVALID_ACTION","Для вычисления напоминания нужен полный срок ISO8601 со смещением зоны.");
                }
            }
            if (absoluteReminders == 1 && !compiledReminders) normalizeFloatingReminder(action);
            result.add(action);
            if ("reschedule".equals(type) && action.has("reminder_at")) {
                var reminder = json.createObjectNode().put("type","remind").put("task_ref",ref);
                reminder.set("reminder_at",action.remove("reminder_at"));result.add(reminder);
            }
        }
        if (result.size()>20) throw new PlanningException("ACTION_LIMIT");
        if (result.stream().noneMatch(a -> !a.path("ambiguous_reason").asText().isBlank()))
            result.sort(Comparator.comparing(a -> "remind".equals(a.path("type").asText())));
        var compiled = json.createArrayNode();result.forEach(compiled::add);return compiled;
    }

    public List<Map<String,Object>> retrievalHints() { return pendingSuggestions.stream().limit(3).map(this::expose).toList(); }

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
        if (smartSearch && actions instanceof com.fasterxml.jackson.databind.node.ArrayNode array)
            DeadlinePrecisionPolicy.normalize(array, sourceWithoutQuotes(), zone);
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
