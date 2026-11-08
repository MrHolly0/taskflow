package ru.taskflow.nlp.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmToolRequest(List<LlmMessage> messages, List<Map<String, Object>> tools, String reasoningEffort, String requiredTool, Boolean jsonOutput) {
    public LlmToolRequest {
        if (reasoningEffort != null && !Set.of("low", "medium", "high").contains(reasoningEffort))
            throw new IllegalArgumentException("unknown reasoning effort");
        if (Boolean.TRUE.equals(jsonOutput) && requiredTool != null)
            throw new IllegalArgumentException("JSON output cannot require a tool");
        if (requiredTool != null && tools.stream().noneMatch(tool ->
                tool.get("function") instanceof Map<?, ?> function && requiredTool.equals(function.get("name"))))
            throw new IllegalArgumentException("required tool is not declared");
    }
    public LlmToolRequest(List<LlmMessage> messages, List<Map<String, Object>> tools, String reasoningEffort, String requiredTool) {
        this(messages, tools, reasoningEffort, requiredTool, null);
    }
    public LlmToolRequest(List<LlmMessage> messages, List<Map<String, Object>> tools, String reasoningEffort) {
        this(messages, tools, reasoningEffort, null, null);
    }
    public LlmToolRequest(List<LlmMessage> messages, List<Map<String, Object>> tools) {
        this(messages, tools, null, null, null);
    }
}
