package ru.taskflow.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface UserRepository extends JpaRepository<UserJpaEntity, UUID> {
    @Modifying
    @Query(value = "UPDATE users SET last_active_at = NOW() WHERE id = :id AND last_active_at < NOW() - INTERVAL '5 minutes'", nativeQuery = true)
    int recordActivity(@Param("id") UUID userId);
}