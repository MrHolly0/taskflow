package ru.taskflow.app.scheduler;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClusterJobRunner {
    private final EntityManager entityManager;

    @Transactional(timeout = 60)
    public void run(long jobId, Runnable job) {
        boolean acquired = Boolean.TRUE.equals(entityManager.createNativeQuery("SELECT pg_try_advisory_xact_lock(:id)")
                .setParameter("id", jobId).getSingleResult());
        if (acquired) job.run();
    }
}
