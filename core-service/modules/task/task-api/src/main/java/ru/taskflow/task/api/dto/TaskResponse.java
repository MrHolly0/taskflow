package ru.taskflow.task.api.dto;

import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        String title,
        String description,
        TaskPriority priority,
        TaskStatus status,
        OffsetDateTime deadline,
        Integer estimateMinutes,
        boolean isDraft,
        TaskSource source,
        UUID groupId,
        String groupName,
        List<String> tags,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt
) {}
