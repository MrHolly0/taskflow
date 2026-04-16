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
        List<String> tags,
        Integer estimateMinutes
) {}
