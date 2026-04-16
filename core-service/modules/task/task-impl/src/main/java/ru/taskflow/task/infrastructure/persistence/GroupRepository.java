package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GroupRepository extends JpaRepository<GroupJpaEntity, UUID> {

    Optional<GroupJpaEntity> findByIdAndUserId(UUID id, UUID userId);
}
