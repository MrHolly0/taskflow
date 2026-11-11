package ru.taskflow.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSettingsRepository extends JpaRepository<UserSettingsJpaEntity, UUID> {

    Optional<UserSettingsJpaEntity> findByUserId(UUID userId);

    @org.springframework.data.jpa.repository.Query(value = "SELECT user_id FROM user_settings WHERE user_id=:userId FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockForUpdate(@org.springframework.data.repository.query.Param("userId") UUID userId);

    List<UserSettingsJpaEntity> findAllByAutoCleanCompletedDaysNotNull();
}
