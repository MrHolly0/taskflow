package ru.taskflow.audit.api;

import ru.taskflow.audit.api.dto.TaskEventResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AuditService {
    void record(UUID userId, UUID taskId, AuditEventType eventType, Map<String, Object> delta);
    List<TaskEventResponse> getHistory(UUID taskId, UUID userId);
}
