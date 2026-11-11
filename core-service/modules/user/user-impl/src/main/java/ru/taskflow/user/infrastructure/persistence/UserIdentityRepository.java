package ru.taskflow.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.taskflow.user.api.IdentityProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentityJpaEntity, UUID> {

    Optional<UserIdentityJpaEntity> findByProviderAndExternalId(IdentityProvider provider, String externalId);

    Optional<UserIdentityJpaEntity> findFirstByUser_IdAndProviderOrderByCreatedAtDescIdAsc(UUID userId, IdentityProvider provider);

    default Optional<UserIdentityJpaEntity> findByUser_IdAndProvider(UUID userId, IdentityProvider provider) {
        return findFirstByUser_IdAndProviderOrderByCreatedAtDescIdAsc(userId, provider);
    }

    List<UserIdentityJpaEntity> findByUser_Id(UUID userId);

    long countByUser_Id(UUID userId);

    // При слиянии сохраняем уже подключённый канал получателя.
    @Modifying
    @Query("""
            UPDATE UserIdentityJpaEntity i SET i.user = :to
            WHERE i.user = :from
              AND i.provider NOT IN (
                  SELECT i2.provider FROM UserIdentityJpaEntity i2 WHERE i2.user = :to
              )
            """)
    int reassignOwner(@Param("from") UserJpaEntity from, @Param("to") UserJpaEntity to);
}
