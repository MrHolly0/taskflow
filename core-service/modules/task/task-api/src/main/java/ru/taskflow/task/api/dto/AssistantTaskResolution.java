package ru.taskflow.task.api.dto;

import java.util.List;

public record AssistantTaskResolution(AssistantTaskSelection selection, List<TaskResponse> suggestions) {
    public AssistantTaskResolution { suggestions = List.copyOf(suggestions); }
}
