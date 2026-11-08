package ru.taskflow.assistant.application.planning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.application.*;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Component
@Primary
public class PlanningRouter implements AssistantPlanner {
    private final AgentLoop direct;
    private final ExperimentalAgentLoop experimental;
    private final PlanningMode mode;
    public PlanningRouter(AgentLoop direct, ExperimentalAgentLoop experimental,
            @Value("${app.assistant.planning-mode:BATCH_PLAN}") String mode) {
        this.direct = direct; this.experimental = experimental;
        this.mode = PlanningMode.valueOf(mode.toUpperCase(Locale.ROOT));
    }
    @Override public AgentOutcome run(UUID user, String text, ZoneId zone, AssistantEntryPoint entry) {
        return mode == PlanningMode.DIRECT ? direct.run(user, text, zone, entry) : experimental.run(mode, user, text, zone, entry);
    }
}
