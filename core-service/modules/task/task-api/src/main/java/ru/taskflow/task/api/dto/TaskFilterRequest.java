package ru.taskflow.task.api.dto;

import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;

import java.util.UUID;

public record TaskFilterRequest(
        UUID groupId,
        TaskStatus status,
        TaskPriority priority,
        String tag
) {
    public TaskFilterRequest() {
        this(null, null, null, null);
    }
}
