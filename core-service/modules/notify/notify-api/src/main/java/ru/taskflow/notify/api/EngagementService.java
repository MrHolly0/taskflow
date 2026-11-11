package ru.taskflow.notify.api;

import java.util.UUID;

public interface EngagementService {
    void plan();
    Reply reply(UUID userId, UUID promptId, String action);

    record Reply(String text, boolean terminal) {}
}
