package ru.taskflow.assistant.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProposalRepository extends JpaRepository<ProposalJpaEntity, UUID> {

    Optional<ProposalJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProposalJpaEntity p WHERE p.id = :id AND p.userId = :userId")
    Optional<ProposalJpaEntity> findForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<ProposalJpaEntity> findByShortCodeAndUserId(String shortCode, UUID userId);

    @Query("""
            SELECT DISTINCT p FROM ProposalJpaEntity p
            LEFT JOIN FETCH p.actions
            WHERE p.id = :id AND p.userId = :userId
            """)
    Optional<ProposalJpaEntity> findWithActions(@Param("id") UUID id, @Param("userId") UUID userId);

    // Pageable вместо LIMIT — JPQL не поддерживает LIMIT напрямую, а Optional<>
    // без ограничения результата упал бы IncorrectResultSizeDataAccessException
    // при второй незакрытой заявке того же пользователя.
    @Query("""
            SELECT DISTINCT p FROM ProposalJpaEntity p
            LEFT JOIN FETCH p.actions
            WHERE p.userId = :userId AND p.status = 'PENDING' AND p.expiresAt > :now
            ORDER BY p.createdAt DESC
            """)
    List<ProposalJpaEntity> findLatestPending(@Param("userId") UUID userId, @Param("now") OffsetDateTime now, Pageable pageable);

    @Modifying
    @Query("UPDATE ProposalJpaEntity p SET p.userId = :to WHERE p.userId = :from")
    int reassignOwner(@Param("from") UUID from, @Param("to") UUID to);

    @Query("""
            SELECT p.sourceText FROM ProposalJpaEntity p
            JOIN p.actions a
            WHERE a.appliedTaskId = :taskId AND p.sourceText IS NOT NULL
            ORDER BY p.createdAt DESC
            """)
    List<String> findSourceTextsByAppliedTaskId(@Param("taskId") UUID taskId, Pageable pageable);
}
