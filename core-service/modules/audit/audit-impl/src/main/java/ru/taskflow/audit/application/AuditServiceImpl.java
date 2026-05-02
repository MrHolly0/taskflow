package ru.taskflow.audit.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.audit.api.dto.TaskEventResponse;
import ru.taskflow.audit.infrastructure.persistence.TaskEventJpaEntity;
import ru.taskflow.audit.infrastructure.persistence.TaskEventRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сервис аудита и логирования действий с задачами.
 *
 * Записывает все операции над задачами (создание, обновление, удаление, изменение статуса)
 * для целей аудита и анализа истории.
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {
    private final TaskEventRepository taskEventRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void record(UUID userId, UUID taskId, AuditEventType eventType, Map<String, Object> delta) {
        try {
            String deltaJson = delta != null ? objectMapper.writeValueAsString(delta) : null;
            TaskEventJpaEntity event = TaskEventJpaEntity.builder()
                .userId(userId)
                .taskId(taskId)
                .eventType(eventType.name())
                .delta(deltaJson)
                .build();
            taskEventRepository.save(event);
        } catch (Exception e) {
            System.err.println("Failed to record audit event for task " + taskId + " by user " + userId + ": " + e.getMessage());
        }
    }

    @Override
    public List<TaskEventResponse> getHistory(UUID taskId, UUID userId) {
        return taskEventRepository.findByTaskIdOrderByOccurredAtDesc(taskId)
            .stream()
            .map(entity -> new TaskEventResponse(
                entity.getId(),
                entity.getTaskId(),
                entity.getUserId(),
                entity.getEventType(),
                deserializeDelta(entity.getDelta()),
                entity.getOccurredAt()
            ))
            .toList();
    }

    private Map<String, Object> deserializeDelta(String deltaJson) {
        if (deltaJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(deltaJson, Map.class);
        } catch (Exception e) {
            System.err.println("Failed to deserialize delta: " + deltaJson + ", error: " + e.getMessage());
            return null;
        }
    }
}
