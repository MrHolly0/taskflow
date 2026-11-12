package ru.taskflow.assistant.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Написан вручную намеренно: генерация маппинга уже приводила к тихой потере
 * поля в TaskMapper, когда имя геттера не совпало с ожидаемым.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProposalMapper {

    private final ObjectMapper objectMapper;
    private final ru.taskflow.user.api.UserService users;
    private final ru.taskflow.assistant.application.SummaryRenderer summaries;

    /**
     * firstPassLatencyMs/secondPassLatencyMs здесь всегда 0: в сущности нет
     * для них столбцов (только общий latency_ms), их некуда прочитать при
     * повторном обращении к сохранённому предложению. Сразу после
     * AgentLoop.run() эти значения ещё живы в AgentOutcome — AssistantServiceImpl
     * накладывает их поверх результата toDto() ровно один раз, для ответа на
     * тот же вызов; из findById/findByShortCode они не восстановятся.
     */
    public Proposal toDto(ProposalJpaEntity entity) {
        return new Proposal(
                entity.getId(),
                entity.getShortCode(),
                entity.getUserId(),
                ProposalStatus.valueOf(entity.getStatus()),
                entity.getSourceText(),
                entity.getClarification(),
                toActions(entity),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.isExclusive(),
                entity.getAmbiguityReason(),
                readRejections(entity.getRejections()),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                entity.getLatencyMs(),
                0,
                0,
                entity.getLlmPasses()
        );
    }

    private List<String> readRejections(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Не удалось разобрать rejections предложения, вернём пустой список: {}", json);
            return List.of();
        }
    }

    private List<ProposedAction> toActions(ProposalJpaEntity entity) {
        var zone=entity.getActions().isEmpty()?java.time.ZoneOffset.UTC:users.getTimezone(entity.getUserId());
        return entity.getActions().stream()
                .sorted(Comparator.comparingInt(ProposalActionJpaEntity::getOrdinal))
                .map(action->toAction(action,zone))
                .toList();
    }

    private ProposedAction toAction(ProposalActionJpaEntity a,java.time.ZoneId zone) {
        var type=AssistantActionType.valueOf(a.getType());
        var payload=readPayload(a.getPayload());
        return new ProposedAction(
                a.getOrdinal(),
                type,
                a.getTargetTaskId(),
                payload,
                summaries.localize(type,a.getSummary(),payload,zone),
                a.isAccepted()
        );
    }

    private Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Не удалось разобрать payload действия, вернём пустой: {}", json);
            return Map.of();
        }
    }
}
