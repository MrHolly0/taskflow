package ru.taskflow.task.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CreateTaskRequest(
        @NotBlank @Size(max = 512) String title,
        String description,
        TaskPriority priority,
        OffsetDateTime deadline,
        UUID groupId,
        String groupName,
        List<String> tags,
        Integer estimateMinutes,
        TaskSource source,
        RecurrenceRule recurrence,
        // Б1: настойчивость по задаче — ставит человек. По умолчанию выключена,
        // как и на TaskJpaEntity.
        boolean persistentReminder,
        // День исполнения — предположение, не обязательство: живёт отдельно
        // от deadline и не участвует в планировании напоминаний.
        OffsetDateTime plannedDate,
        Boolean skipDefaultReminders,
        OffsetDateTime reminderAt,
        @Size(max=5) List<OffsetDateTime> reminderAts
) {
    public CreateTaskRequest(String title,String description,TaskPriority priority,OffsetDateTime deadline,
            UUID groupId,String groupName,List<String> tags,Integer estimateMinutes,TaskSource source,
            RecurrenceRule recurrence,boolean persistentReminder,OffsetDateTime plannedDate,Boolean skipDefaultReminders,OffsetDateTime reminderAt) {
        this(title,description,priority,deadline,groupId,groupName,tags,estimateMinutes,source,recurrence,persistentReminder,plannedDate,skipDefaultReminders,reminderAt,List.of());
    }
    public List<OffsetDateTime> explicitReminders() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.ofNullable(reminderAt),reminderAts.stream())
                .map(value->value.withOffsetSameInstant(java.time.ZoneOffset.UTC)).distinct().sorted().toList();
    }
    public CreateTaskRequest(String title, String description, TaskPriority priority, OffsetDateTime deadline,
            UUID groupId, String groupName, List<String> tags, Integer estimateMinutes, TaskSource source,
            RecurrenceRule recurrence, boolean persistentReminder, OffsetDateTime plannedDate) {
        this(title, description, priority, deadline, groupId, groupName, tags, estimateMinutes, source,
                recurrence, persistentReminder, plannedDate, null, null);
    }

    public CreateTaskRequest {
        if (priority == null) priority = TaskPriority.MEDIUM;
        if (source == null) source = TaskSource.MANUAL;
        if (tags == null) tags = List.of();
        reminderAts = reminderAts==null?List.of():List.copyOf(reminderAts);
    }

    public CreateTaskRequest(String title, String description, TaskPriority priority, OffsetDateTime deadline,
            UUID groupId, String groupName, List<String> tags, Integer estimateMinutes, TaskSource source) {
        this(title, description, priority, deadline, groupId, groupName, tags, estimateMinutes, source, null, false,
                null);
    }

    // Совместимость: до Б1 канонический вид заканчивался на recurrence.
    public CreateTaskRequest(String title, String description, TaskPriority priority, OffsetDateTime deadline,
            UUID groupId, String groupName, List<String> tags, Integer estimateMinutes, TaskSource source,
            RecurrenceRule recurrence) {
        this(title, description, priority, deadline, groupId, groupName, tags, estimateMinutes, source, recurrence,
                false, null);
    }

    // Совместимость: до дня исполнения канонический вид заканчивался на persistentReminder.
    public CreateTaskRequest(String title, String description, TaskPriority priority, OffsetDateTime deadline,
            UUID groupId, String groupName, List<String> tags, Integer estimateMinutes, TaskSource source,
            RecurrenceRule recurrence, boolean persistentReminder) {
        this(title, description, priority, deadline, groupId, groupName, tags, estimateMinutes, source, recurrence,
                persistentReminder, null);
    }
}
