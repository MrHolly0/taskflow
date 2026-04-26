package ru.taskflow.task.api.dto;

import java.util.List;

public record FocusResponse(
        List<TaskResponse> tasks
) {}
