package ru.taskflow.task.api.dto;

import java.util.List;

public record DigestResponse(
        List<TaskResponse> topTasks,
        long totalTasks,
        long completedToday,
        long overdueTasks
) {}
