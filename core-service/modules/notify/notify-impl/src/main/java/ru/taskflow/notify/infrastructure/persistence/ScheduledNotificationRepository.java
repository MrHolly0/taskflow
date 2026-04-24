package ru.taskflow.notify.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ScheduledNotificationRepository extends JpaRepository<ScheduledNotificationJpaEntity, UUID> {

    @Modifying
    @Query("DELETE FROM ScheduledNotificationJpaEntity s WHERE s.taskId = :taskId AND s.sent = false")
    void deleteUnsentByTaskId(UUID taskId);
}
