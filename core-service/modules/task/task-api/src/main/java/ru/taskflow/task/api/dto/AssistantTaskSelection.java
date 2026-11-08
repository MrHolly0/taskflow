package ru.taskflow.task.api.dto;

import java.util.List;

public record AssistantTaskSelection(List<TaskResponse> tasks, boolean truncated) {}
