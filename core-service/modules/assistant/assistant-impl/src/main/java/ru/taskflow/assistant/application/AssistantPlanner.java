package ru.taskflow.assistant.application;

import ru.taskflow.assistant.api.AssistantEntryPoint;
import java.time.ZoneId;
import java.util.UUID;

public interface AssistantPlanner {
    AgentOutcome run(UUID userId, String text, ZoneId zone, AssistantEntryPoint entryPoint);
}
