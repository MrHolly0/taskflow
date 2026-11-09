package ru.taskflow.task.api.dto;

import jakarta.validation.constraints.Size;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record UpdateTaskRequest(
        @Size(max = 512) String title,
        String description,
        TaskPriority priority,
        TaskStatus status,
        OffsetDateTime deadline,
        UUID groupId,
        String groupName,
        List<String> tags,
        Integer estimateMinutes,
        OffsetDateTime plannedDate,
        RecurrenceRule recurrence,
        // Б1: null — не трогать; true/false — включить/выключить настойчивость.
        // Выключение отдельно гасит запланированные повторы (TaskServiceImpl.update).
        Boolean persistentReminder,
        Boolean clearGroup,
        Boolean clearPlannedDate,
        Boolean clearDeadline,
        Boolean clearEstimateMinutes
) {
    public UpdateTaskRequest(String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, UUID groupId, String groupName, List<String> tags, Integer estimateMinutes,
            OffsetDateTime plannedDate, RecurrenceRule recurrence, Boolean persistentReminder,
            Boolean clearGroup, Boolean clearPlannedDate) {
        this(title, description, priority, status, deadline, groupId, groupName, tags, estimateMinutes,
                plannedDate, recurrence, persistentReminder, clearGroup, clearPlannedDate, null, null);
    }

    public UpdateTaskRequest(String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, UUID groupId, String groupName, List<String> tags, Integer estimateMinutes) {
        this(title, description, priority, status, deadline, groupId, groupName, tags, estimateMinutes, null, null,
                null);
    }

    public UpdateTaskRequest(String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, UUID groupId, String groupName, List<String> tags, Integer estimateMinutes,
            OffsetDateTime plannedDate) {
        this(title, description, priority, status, deadline, groupId, groupName, tags, estimateMinutes, plannedDate,
                null, null);
    }

    // Совместимость: до Б1 канонический вид заканчивался на recurrence.
    public UpdateTaskRequest(String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, UUID groupId, String groupName, List<String> tags, Integer estimateMinutes,
            OffsetDateTime plannedDate, RecurrenceRule recurrence) {
        this(title, description, priority, status, deadline, groupId, groupName, tags, estimateMinutes, plannedDate,
                recurrence, null);
    }

    public UpdateTaskRequest(String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, UUID groupId, String groupName, List<String> tags, Integer estimateMinutes,
            OffsetDateTime plannedDate, RecurrenceRule recurrence, Boolean persistentReminder) {
        this(title, description, priority, status, deadline, groupId, groupName, tags, estimateMinutes, plannedDate,
                recurrence, persistentReminder, null, null);
    }
}
