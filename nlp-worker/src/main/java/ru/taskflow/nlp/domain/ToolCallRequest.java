package ru.taskflow.nlp.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ToolCallRequest(List<ToolCallMessage> messages, List<Map<String, Object>> tools, String reasoningEffort, String requiredTool, Boolean jsonOutput) {
    public ToolCallRequest {
        if (reasoningEffort != null && !Set.of("low", "medium", "high").contains(reasoningEffort))
            throw new IllegalArgumentException("unknown reasoning effort");
        if (Boolean.TRUE.equals(jsonOutput) && requiredTool != null)
            throw new IllegalArgumentException("JSON output cannot require a tool");
        if (requiredTool != null && tools.stream().noneMatch(tool ->
                tool.get("function") instanceof Map<?, ?> function && requiredTool.equals(function.get("name"))))
            throw new IllegalArgumentException("required tool is not declared");
    }
    public ToolCallRequest(List<ToolCallMessage> messages, List<Map<String, Object>> tools, String reasoningEffort, String requiredTool) {
        this(messages, tools, reasoningEffort, requiredTool, null);
    }
    public ToolCallRequest(List<ToolCallMessage> messages, List<Map<String, Object>> tools, String reasoningEffort) {
        this(messages, tools, reasoningEffort, null, null);
    }
    public ToolCallRequest(List<ToolCallMessage> messages, List<Map<String, Object>> tools) {
        this(messages, tools, null, null, null);
    }
}
