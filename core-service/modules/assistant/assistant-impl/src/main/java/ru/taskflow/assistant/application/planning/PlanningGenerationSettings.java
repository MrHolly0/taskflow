package ru.taskflow.assistant.application.planning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class PlanningGenerationSettings {
    private final String reasoningEffort;
    public PlanningGenerationSettings(@Value("${app.assistant.experimental-reasoning-effort:low}") String effort) {
        reasoningEffort = effort == null || effort.isBlank() || "default".equals(effort) ? null : effort;
        if (reasoningEffort != null && !Set.of("low", "medium", "high").contains(reasoningEffort))
            throw new IllegalArgumentException("unknown reasoning effort");
    }
    public String reasoningEffort(int attempt) {
        return attempt > 0 && "low".equals(reasoningEffort) ? "medium" : reasoningEffort;
    }
}
