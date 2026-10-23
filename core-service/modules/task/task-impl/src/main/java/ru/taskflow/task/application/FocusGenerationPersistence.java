package ru.taskflow.task.application;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.task.api.exception.TaskNotFoundException;
import ru.taskflow.task.infrastructure.persistence.TaskJpaEntity;
import ru.taskflow.task.infrastructure.persistence.TaskRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FocusGenerationPersistence {

    private final TaskRepository tasks;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public TaskJpaEntity find(UUID userId, UUID taskId) {
        return tasks.findByIdAndUserId(taskId, userId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    @Transactional
    public boolean claimPlannedDate(UUID userId, UUID taskId, OffsetDateTime now) {
        return entityManager.createNativeQuery("""
                UPDATE tasks SET planned_date_suggestion_attempted_at = :now
                WHERE id = :taskId AND user_id = :userId AND is_deleted = false
                  AND status IN ('TODO', 'IN_PROGRESS')
                  AND deadline IS NULL AND planned_date IS NULL
                  AND planned_date_suggestion_attempted_at IS NULL
                """)
                .setParameter("now", now).setParameter("taskId", taskId).setParameter("userId", userId)
                .executeUpdate() == 1;
    }

    @Transactional
    public void savePlannedDate(UUID userId, TaskJpaEntity original, OffsetDateTime now, OffsetDateTime plannedDate) {
        entityManager.createNativeQuery("""
                UPDATE tasks SET planned_date = :plannedDate, planned_date_set_at = :now
                WHERE id = :taskId AND user_id = :userId AND is_deleted = false
                  AND status IN ('TODO', 'IN_PROGRESS') AND deadline IS NULL AND planned_date IS NULL
                  AND planned_date_suggestion_attempted_at = :now
                  AND title = :title AND description IS NOT DISTINCT FROM :description
                """)
                .setParameter("plannedDate", plannedDate).setParameter("now", now)
                .setParameter("taskId", original.getId()).setParameter("userId", userId)
                .setParameter("title", original.getTitle()).setParameter("description", original.getDescription())
                .executeUpdate();
    }

    @Transactional
    public boolean claimHint(UUID userId, UUID taskId, OffsetDateTime now) {
        return entityManager.createNativeQuery("""
                UPDATE tasks SET first_step_generated_at = :now
                WHERE id = :taskId AND user_id = :userId AND is_deleted = false
                  AND status IN ('TODO', 'IN_PROGRESS') AND first_step_generated_at IS NULL
                """)
                .setParameter("now", now).setParameter("taskId", taskId).setParameter("userId", userId)
                .executeUpdate() == 1;
    }

    @Transactional
    public void saveHint(UUID userId, TaskJpaEntity original, OffsetDateTime now, String hint) {
        entityManager.createNativeQuery("""
                UPDATE tasks SET first_step_hint = :hint
                WHERE id = :taskId AND user_id = :userId AND is_deleted = false
                  AND status IN ('TODO', 'IN_PROGRESS') AND first_step_generated_at = :now
                  AND title = :title AND description IS NOT DISTINCT FROM :description
                """)
                .setParameter("hint", hint).setParameter("now", now)
                .setParameter("taskId", original.getId()).setParameter("userId", userId)
                .setParameter("title", original.getTitle()).setParameter("description", original.getDescription())
                .executeUpdate();
    }
}
