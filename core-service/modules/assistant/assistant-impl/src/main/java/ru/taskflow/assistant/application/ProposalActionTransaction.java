package ru.taskflow.assistant.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.assistant.infrastructure.persistence.ProposalActionJpaEntity;

import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class ProposalActionTransaction {

    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID execute(UUID actionId, Supplier<UUID> operation) {
        var action = entityManager.find(ProposalActionJpaEntity.class, actionId, LockModeType.PESSIMISTIC_WRITE);
        if (action.getAppliedTaskId() != null) {
            return action.getAppliedTaskId();
        }
        UUID taskId = operation.get();
        action.setAppliedTaskId(taskId);
        action.setApplyError(null);
        return taskId;
    }
}
