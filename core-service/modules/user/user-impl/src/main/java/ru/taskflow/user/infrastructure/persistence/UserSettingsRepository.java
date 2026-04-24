package ru.taskflow.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSettingsRepository extends JpaRepository<UserSettingsJpaEntity, UUID> {

    Optional<UserSettingsJpaEntity> findByUserId(UUID userId);
}
