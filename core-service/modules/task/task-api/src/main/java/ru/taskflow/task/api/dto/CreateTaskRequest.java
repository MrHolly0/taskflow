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
        TaskSource source
) {
    public CreateTaskRequest {
        if (priority == null) priority = TaskPriority.MEDIUM;
        if (source == null) source = TaskSource.MANUAL;
        if (tags == null) tags = List.of();
    }
}
