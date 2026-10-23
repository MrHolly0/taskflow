package ru.taskflow.notificationworker.infrastructure.db;

import java.util.UUID;

public record PendingNotification(
        UUID id,
        String channel,
        String destination,
        String payloadType,
        String payload,
        UUID leaseToken,
        int attempt
) {}
