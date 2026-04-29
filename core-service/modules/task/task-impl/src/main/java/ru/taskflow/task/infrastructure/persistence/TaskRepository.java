package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<TaskJpaEntity, UUID> {

    Optional<TaskJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group g
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.isDraft = false
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

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.status != :done
              AND t.isDraft = false
              AND t.isDeleted = false
              AND (t.deadline IS NULL OR t.deadline <= :endOfToday)
            ORDER BY CASE t.priority
              WHEN 'URGENT' THEN 0
              WHEN 'HIGH' THEN 1
              WHEN 'NORMAL' THEN 2
              ELSE 3
            END,
            CASE WHEN t.deadline IS NULL THEN 1 ELSE 0 END,
            t.deadline
            """)
    List<TaskJpaEntity> findFocusTasks(
            @Param("userId") UUID userId,
            @Param("done") TaskStatus done,
            @Param("endOfToday") OffsetDateTime endOfToday
    );

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.status != :done
              AND t.isDraft = false
              AND t.isDeleted = false
              AND (DATE(t.deadline) = DATE(:date) OR (t.deadline IS NULL))
            ORDER BY CASE t.priority
              WHEN 'URGENT' THEN 0
              WHEN 'HIGH' THEN 1
              WHEN 'NORMAL' THEN 2
              ELSE 3
            END,
            t.createdAt DESC
            """)
    List<TaskJpaEntity> findDigestTasks(
            @Param("userId") UUID userId,
            @Param("date") OffsetDateTime date,
            @Param("done") TaskStatus done
    );

}
