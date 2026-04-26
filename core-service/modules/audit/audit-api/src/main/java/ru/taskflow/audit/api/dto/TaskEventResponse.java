package ru.taskflow.audit.api.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record TaskEventResponse(
    UUID id,
    UUID taskId,
    UUID userId,
    String eventType,
    Map<String, Object> delta,
    OffsetDateTime occurredAt
) {}
