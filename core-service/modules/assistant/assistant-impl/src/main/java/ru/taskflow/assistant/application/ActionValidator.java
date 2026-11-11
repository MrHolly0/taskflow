package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.exception.TaskNotFoundException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ActionValidator {

    private final TaskService taskService;
    private final Clock clock;

    private static final Set<String> UPDATABLE_FIELDS = Set.of("title", "description", "priority", "group");

    public record ValidationResult(boolean valid, String error, UUID targetTaskId) {

        static ValidationResult ok(UUID targetTaskId) {
            return new ValidationResult(true, null, targetTaskId);
        }

        static ValidationResult fail(String error) {
            return new ValidationResult(false, error, null);
        }

        /**
         * Отказ после того, как ярлык уже разрешился в задачу: не «неизвестная
         * ссылка», а «ссылка есть, но остальное не собралось» (нечего менять,
         * не разобрался срок). targetTaskId остаётся — вызывающая сторона
         * (двоякость в AgentLoop) должна знать, о какой задаче шла речь, даже
         * когда самого действия не будет.
         */
        static ValidationResult failForTask(String error, UUID targetTaskId) {
            return new ValidationResult(false, error, targetTaskId);
        }
    }

    public ValidationResult validate(AssistantActionType type,
                                     Map<String, Object> args, TaskContextWindow window) {
        if (type == AssistantActionType.CREATE) {
            return validateCreate(args);
        }

        Object rawRef = args.get("task_ref");
        if (rawRef == null || rawRef.toString().isBlank()) {
            return ValidationResult.fail("не указан task_ref");
        }
        String ref = rawRef.toString().trim();
        UUID taskId = window.resolve(ref);
        if (taskId == null) {
            return ValidationResult.fail("неизвестный ярлык задачи: " + ref);
        }

        return switch (type) {
            case RESCHEDULE -> validateReschedule(args, taskId);
            case UPDATE -> validateUpdate(args, taskId);
            case REMIND -> validateRemind(args, taskId);
            case COMPLETE, CANCEL -> ValidationResult.ok(taskId);
            case CREATE -> throw new IllegalStateException("CREATE обработан выше");
        };
    }

    public ValidationResult revalidateForApply(UUID userId, AssistantActionType type, UUID targetTaskId) {
        if (type == AssistantActionType.CREATE) {
            return ValidationResult.ok(null);
        }
        TaskResponse task;
        try {
            task = taskService.findById(userId, targetTaskId);
        } catch (TaskNotFoundException e) {
            return ValidationResult.fail("задача не найдена или недоступна");
        }
        if (task.status() == TaskStatus.DONE
                && (type == AssistantActionType.COMPLETE || type == AssistantActionType.REMIND)) {
            return ValidationResult.fail("задача уже закрыта");
        }
        if (task.status() == TaskStatus.CANCELLED) {
            return ValidationResult.fail("задача уже отменена");
        }
        return ValidationResult.ok(targetTaskId);
    }

    private ValidationResult validateCreate(Map<String, Object> args) {
        Object title = args.get("title");
        if (title == null || title.toString().isBlank()) {
            return ValidationResult.fail("не указан title");
        }
        if (args.containsKey("priority") && !isKnownPriority(args.get("priority"))) {
            return ValidationResult.fail("неизвестный приоритет: " + args.get("priority"));
        }
        if (args.containsKey("deadline") && !isParseableDeadline(args.get("deadline"))) {
            return ValidationResult.fail("не удалось разобрать срок: " + args.get("deadline"));
        }
        if (args.containsKey("planned_date")) {
            OffsetDateTime planned = parseTimeOrNull(args.get("planned_date"));
            if (planned == null) return ValidationResult.fail("не удалось разобрать день работы");
            OffsetDateTime deadline = parseTimeOrNull(args.get("deadline"));
            if (deadline != null && planned.isAfter(deadline))
                return ValidationResult.fail("день работы не может быть позже срока задачи");
        }
        if (args.containsKey("recurrence")) {
            return ValidationResult.fail("повторяющиеся задачи пока не поддерживаются");
        }
        if (args.containsKey("reminder_at")) {
            OffsetDateTime reminderAt = parseTimeOrNull(args.get("reminder_at"));
            if (reminderAt == null) {
                return ValidationResult.fail("не удалось разобрать время напоминания: " + args.get("reminder_at"));
            }
            if (reminderAt.isBefore(OffsetDateTime.now(clock))) {
                return ValidationResult.fail("время напоминания уже прошло");
            }
        }
        if(args.containsKey("reminder_ats")) {
            if(!(args.get("reminder_ats") instanceof java.util.List<?> values) || values.isEmpty() || values.size()>5)
                return ValidationResult.fail("reminder_ats должен содержать от одного до пяти времён");
            var unique=new java.util.HashSet<java.time.Instant>();
            if(args.get("reminder_at")!=null) unique.add(parseTimeOrNull(args.get("reminder_at")).toInstant());
            for(var value:values) {
                var at=parseTimeOrNull(value);
                if(at==null) return ValidationResult.fail("не удалось разобрать время напоминания: "+value);
                if(!at.isAfter(OffsetDateTime.now(clock))) return ValidationResult.fail("время напоминания уже прошло");
                unique.add(at.toInstant());
            }
            if(unique.size()>5) return ValidationResult.fail("не больше пяти напоминаний на создание задачи");
        }
        return ValidationResult.ok(null);
    }

    // Та же прикладная валидация, что и у остальных действий (В): прошедшее
    // время отклоняется, чужая задача недоступна — второе уже обеспечивает
    // общий блок разрешения ярлыка выше, здесь только время.
    private ValidationResult validateRemind(Map<String, Object> args, UUID taskId) {
        Object reminderAt = args.get("reminder_at");
        if (reminderAt == null || reminderAt.toString().isBlank()) {
            return ValidationResult.failForTask("не указано reminder_at", taskId);
        }
        OffsetDateTime parsed = parseTimeOrNull(reminderAt);
        if (parsed == null) {
            return ValidationResult.failForTask("не удалось разобрать время напоминания: " + reminderAt, taskId);
        }
        if (parsed.isBefore(OffsetDateTime.now(clock))) {
            return ValidationResult.failForTask("время напоминания уже прошло", taskId);
        }
        return ValidationResult.ok(taskId);
    }

    private ValidationResult validateReschedule(Map<String, Object> args, UUID taskId) {
        Object deadline = args.get("new_deadline");
        if (deadline == null || deadline.toString().isBlank()) {
            return ValidationResult.failForTask("не указан new_deadline", taskId);
        }
        if (!isParseableDeadline(deadline)) {
            return ValidationResult.failForTask("не удалось разобрать срок: " + deadline, taskId);
        }
        return ValidationResult.ok(taskId);
    }

    private ValidationResult validateUpdate(Map<String, Object> args, UUID taskId) {
        boolean hasChange = UPDATABLE_FIELDS.stream()
                .anyMatch(f -> args.get(f) != null && !args.get(f).toString().isBlank());
        if (!hasChange) {
            return ValidationResult.failForTask("нечего менять: не указано ни одного поля", taskId);
        }
        if (args.containsKey("priority") && !isKnownPriority(args.get("priority"))) {
            return ValidationResult.failForTask("неизвестный приоритет: " + args.get("priority"), taskId);
        }
        return ValidationResult.ok(taskId);
    }

    private boolean isKnownPriority(Object raw) {
        if (raw == null) {
            return false;
        }
        try {
            TaskPriority.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isParseableDeadline(Object raw) {
        if (raw == null) {
            return false;
        }
        try {
            OffsetDateTime.parse(raw.toString().trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private OffsetDateTime parseTimeOrNull(Object raw) {
        try {
            return OffsetDateTime.parse(raw.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
