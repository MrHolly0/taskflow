package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.shared.exception.RateLimitExceededException;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class ProposalApplyTransaction {

    private final PlatformTransactionManager transactionManager;
    // У подтверждения две транзакции: оставляем соединения для вложенных действий.
    private final Semaphore concurrentApplies = new Semaphore(2);

    public ApplyResult execute(Supplier<ApplyResult> operation) {
        if (!concurrentApplies.tryAcquire()) {
            throw new RateLimitExceededException("Предыдущие изменения ещё сохраняются, попробуй через несколько секунд");
        }
        try {
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setTimeout(30);
            return transaction.execute(status -> operation.get());
        } finally {
            concurrentApplies.release();
        }
    }
}
