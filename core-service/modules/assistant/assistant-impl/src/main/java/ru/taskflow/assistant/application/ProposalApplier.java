package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ActionOutcome;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.infrastructure.persistence.ProposalActionJpaEntity;
import ru.taskflow.assistant.infrastructure.persistence.ProposalJpaEntity;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.shared.exception.AccessDeniedException;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProposalApplier {

    private static final String GENERIC_FAILURE_MESSAGE = "не удалось применить действие";

    private final ActionValidator actionValidator;
    private final TaskService taskService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ProposalActionTransaction actionTransaction;
    private final ru.taskflow.task.api.TaskOperationService operations;

    public ApplyResult apply(UUID userId, ProposalJpaEntity proposal) { return apply(userId,proposal,false); }
    public ApplyResult apply(UUID userId, ProposalJpaEntity proposal,boolean reversible) {
        List<ProposalActionJpaEntity> accepted = proposal.getActions().stream()
                .filter(ProposalActionJpaEntity::isAccepted)
                .sorted(Comparator.comparingInt(ProposalActionJpaEntity::getOrdinal))
                .toList();

        if (proposal.isExclusive() && accepted.size() > 1)
            throw new ValidationException("Выбери один вариант перед применением предложения");

        List<ActionOutcome> outcomes = new ArrayList<>();
        int appliedCount = 0;

        for (ProposalActionJpaEntity action : accepted) {
            if (applyOne(userId, proposal, action,reversible)) {
                appliedCount++;
                var receipt = operations.find(userId, action.getId());
                outcomes.add(new ActionOutcome(action.getOrdinal(), action.getSummary(), true, null,
                        action.getAppliedTaskId(), receipt.map(r -> r.id()).orElse(null),
                        receipt.map(r -> r.expiresAt()).orElse(null), receipt.map(r -> r.undone()).orElse(false)));
            } else {
                outcomes.add(new ActionOutcome(action.getOrdinal(), action.getSummary(), false, action.getApplyError()));
            }
        }

        ProposalStatus status = resolveStatus(appliedCount, accepted.size());
        proposal.setStatus(status.name());
        proposal.setResolvedAt(OffsetDateTime.now());

        return new ApplyResult(status, appliedCount, accepted.size(), outcomes);
    }

    private boolean applyOne(UUID userId, ProposalJpaEntity proposal, ProposalActionJpaEntity action,boolean reversible) {
        if (action.getAppliedTaskId() != null) {
            return true;
        }
        try {
            AssistantActionType type = AssistantActionType.valueOf(action.getType());
            Map<String, Object> payload = readPayload(action.getPayload());
            UUID appliedTaskId = actionTransaction.execute(action.getId(), () -> {
                var validation = actionValidator.revalidateForApply(userId, type, action.getTargetTaskId());
                if (!validation.valid()) {
                    throw new ActionApplyException(validation.error());
                }
                return dispatch(userId, proposal, type, validation.targetTaskId(), payload,reversible?action.getId():null);
            });
            action.setAppliedTaskId(appliedTaskId);
            action.setApplyError(null);
            recordAudit(userId, appliedTaskId, type, payload, action.getOrdinal());
            return true;
        } catch (Exception e) {
            log.warn("Не удалось применить действие {} предложения: {}", action.getOrdinal(), e.getMessage());
            action.setApplyError(safeMessage(e));
            return false;
        }
    }

    /**
     * Доменные исключения (ActionApplyException — наши собственные отказы после
     * разбора payload, и NotFoundException/AccessDeniedException из TaskService)
     * несут безопасный русский текст, который сформулирован для показа пользователю —
     * его можно отдать как есть. Всё остальное (NPE, ошибки БД, таймауты) — обобщённым
     * сообщением, чтобы наружу не утекли стектрейсы и внутренние детали.
     */
    private String safeMessage(Exception e) {
        if (e instanceof ActionApplyException || e instanceof NotFoundException || e instanceof AccessDeniedException) {
            return e.getMessage();
        }
        return GENERIC_FAILURE_MESSAGE;
    }

    /** Сигнализирует отказ конкретного действия по причине, безопасной для показа пользователю. */
    private static final class ActionApplyException extends RuntimeException {
        ActionApplyException(String message) {
            super(message);
        }
    }

    private void recordAudit(UUID userId, UUID taskId, AssistantActionType type,
                              Map<String, Object> payload, int ordinal) {
        try {
            auditService.record(userId, taskId, eventTypeFor(type), payload);
        } catch (Exception e) {
            log.warn("Действие {} применено, но не записалось в аудит: {}", ordinal, e.getMessage());
        }
    }

    private UUID dispatch(UUID userId, ProposalJpaEntity proposal, AssistantActionType type,
                           UUID targetTaskId, Map<String, Object> payload,UUID operation) {
        return switch (type) {
            case CREATE -> createTask(userId, proposal, payload,operation);
            case COMPLETE -> {
                if(operation!=null) operations.complete(userId,targetTaskId,operation);
                else taskService.complete(userId, targetTaskId);
                yield targetTaskId;
            }
            case RESCHEDULE -> {
                // Срок обязателен по контракту RESCHEDULE: если модель дала значение,
                // которое не разобралось, это отказ, а не успешное "ничего не изменить".
                OffsetDateTime deadline = parseDeadline(payload.get("new_deadline"));
                if (deadline == null) {
                    throw new ActionApplyException("не удалось распознать новый срок");
                }
                UpdateTaskRequest request = new UpdateTaskRequest(
                        null, null, null, null, deadline, null, null, null, null);
                taskService.update(userId, targetTaskId, request);
                yield targetTaskId;
            }
            case UPDATE -> {
                String title = asString(payload.get("title"));
                String description = asString(payload.get("description"));
                TaskPriority priority = parsePriority(payload.get("priority"));
                String groupName = asString(payload.get("group"));
                if (title == null && description == null && priority == null && groupName == null) {
                    throw new ActionApplyException("не осталось изменяемых полей после разбора");
                }
                UpdateTaskRequest request = new UpdateTaskRequest(
                        title, description, priority, null, null, null, groupName, null, null);
                taskService.update(userId, targetTaskId, request);
                yield targetTaskId;
            }
            case CANCEL -> {
                UpdateTaskRequest request = new UpdateTaskRequest(
                        null, null, null, TaskStatus.CANCELLED, null, null, null, null, null);
                taskService.update(userId, targetTaskId, request);
                yield targetTaskId;
            }
            case REMIND -> {
                // Срок обязателен по контракту REMIND так же, как new_deadline у
                // RESCHEDULE: дошедшее сюда действие уже прошло ActionValidator,
                // неразобранное значение на этом шаге — отказ, не «ничего не менять».
                OffsetDateTime reminderAt = parseDeadline(payload.get("reminder_at"));
                if (reminderAt == null) {
                    throw new ActionApplyException("не удалось распознать время напоминания");
                }
                taskService.scheduleReminder(userId, targetTaskId, reminderAt);
                yield targetTaskId;
            }
        };
    }

    private UUID createTask(UUID userId, ProposalJpaEntity proposal, Map<String, Object> payload,UUID operation) {
        // День работы независим от дедлайна. Он приходит из явной просьбы
        // пользователя или правки в карточке подтверждения.
        CreateTaskRequest request = new CreateTaskRequest(
                asString(payload.get("title")),
                asString(payload.get("description")),
                parsePriority(payload.get("priority")),
                parseDeadline(payload.get("deadline")),
                null,
                asString(payload.get("group")),
                asTags(payload.get("tags")),
                null,
                sourceOf(proposal),
                null,
                false,
                parseDeadline(payload.get("planned_date")),
                payload.get("reminder_at") != null || Boolean.TRUE.equals(payload.get("no_reminder_needed")),
                parseDeadline(payload.get("reminder_at"))
        );
        TaskResponse created = operation==null?taskService.createQuick(userId, request):operations.create(userId,request,operation);
        return created.id();
    }

    private TaskSource sourceOf(ProposalJpaEntity proposal) {
        if ("WEB".equals(proposal.getSourceChannel())) {
            return TaskSource.ASSISTANT_WEB;
        }
        return "VOICE".equals(proposal.getInputKind()) ? TaskSource.ASSISTANT_BOT_VOICE : TaskSource.ASSISTANT_BOT_TEXT;
    }

    private ProposalStatus resolveStatus(int appliedCount, int totalCount) {
        if (totalCount == 0) {
            return ProposalStatus.APPLIED;
        }
        if (appliedCount == totalCount) {
            return ProposalStatus.APPLIED;
        }
        if (appliedCount == 0) {
            return ProposalStatus.FAILED;
        }
        return ProposalStatus.PARTIALLY_APPLIED;
    }

    /**
     * CREATE -> CREATED и UPDATE -> UPDATED очевидны. COMPLETE и CANCEL меняют
     * TaskStatus, поэтому STATUS_CHANGED. RESCHEDULE формально меняет только
     * дедлайн, а не статус, но выделенного события для срока в AuditEventType
     * нет — STATUS_CHANGED здесь ближе по смыслу к "что-то изменилось в жизненном
     * цикле задачи", чем UPDATED, которое в остальном коде обозначает правку полей
     * содержимого (title/description/priority). REMIND по той же причине: не
     * поле задачи и не статус, но выделенного события для напоминаний нет тоже.
     */
    private AuditEventType eventTypeFor(AssistantActionType type) {
        return switch (type) {
            case CREATE -> AuditEventType.CREATED;
            case COMPLETE, RESCHEDULE, CANCEL, REMIND -> AuditEventType.STATUS_CHANGED;
            case UPDATE -> AuditEventType.UPDATED;
        };
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

    private String asString(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isBlank() ? null : value;
    }

    private List<String> asTags(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }

    private TaskPriority parsePriority(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TaskPriority.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private OffsetDateTime parseDeadline(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
