package ru.taskflow.notificationworker.infrastructure.db;

import java.util.UUID;

public record PendingNotification(
    UUID id,
    Long telegramChatId,
    String payloadType,
    String payload
) {}
