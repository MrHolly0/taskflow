package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;

import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<TaskJpaEntity, UUID> {

    Optional<TaskJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group g
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND (:groupId IS NULL OR t.group.id = :groupId)
              AND (:status IS NULL OR t.status = :status)
              AND (:priority IS NULL OR t.priority = :priority)
              AND (:tag IS NULL OR EXISTS (
                  SELECT 1 FROM t.tags tag WHERE tag.name = :tag
              ))
            """)
    Page<TaskJpaEntity> findAllWithFilter(
            @Param("userId") UUID userId,
            @Param("groupId") UUID groupId,
            @Param("status") TaskStatus status,
            @Param("priority") TaskPriority priority,
            @Param("tag") String tag,
            Pageable pageable
    );
}
