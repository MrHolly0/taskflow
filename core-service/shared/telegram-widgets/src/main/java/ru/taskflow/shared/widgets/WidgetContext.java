package ru.taskflow.shared.widgets;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WidgetContext(UUID id, UUID userId, String kind, UUID referenceId, UUID taskId,
                            OffsetDateTime deadline, long chatId, Long messageId, String transport,
                            OffsetDateTime expiresAt, long revision, int page, boolean expanded, WidgetState state) {}
