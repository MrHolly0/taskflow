package ru.taskflow.audit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskEventRepository extends JpaRepository<TaskEventJpaEntity, UUID> {
    List<TaskEventJpaEntity> findByTaskIdOrderByOccurredAtDesc(UUID taskId);
}
