package ru.taskflow.notify.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ScheduledNotificationRepository extends JpaRepository<ScheduledNotificationJpaEntity, UUID> {

    @Modifying
    @Query("UPDATE ScheduledNotificationJpaEntity s SET s.payload = :payload WHERE s.userId = :userId AND s.taskId = :taskId AND s.sent = false AND s.payloadType = 'TASK_REMINDER'")
    int updateUnsentPayload(@Param("userId") UUID userId, @Param("taskId") UUID taskId, @Param("payload") String payload);

    @Modifying
    @Query("DELETE FROM ScheduledNotificationJpaEntity s WHERE s.taskId = :taskId AND s.sent = false")
    void deleteUnsentByTaskId(UUID taskId);

    // Снятие одного напоминания не должно задеть доставку по соседним
    // напоминаниям той же задачи (А2) — отсюда отдельный ключ, не taskId.
    @Modifying
    @Query("DELETE FROM ScheduledNotificationJpaEntity s WHERE s.reminderId = :reminderId AND s.sent = false")
    void deleteUnsentByReminderId(@Param("reminderId") UUID reminderId);

    @Modifying
    @Query(value = "UPDATE engagement_prompts SET cancelled_at = NOW() WHERE user_id = :userId AND cancelled_at IS NULL", nativeQuery = true)
    int cancelEngagementOwner(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM scheduled_notifications WHERE user_id = :userId AND engagement_prompt_id IS NOT NULL AND sent = false", nativeQuery = true)
    int deleteUnsentEngagementOwner(@Param("userId") UUID userId);

    // userId и destination в одном UPDATE: если делать это двумя отдельными
    // запросами, после первого перенесённые строки станут неотличимы (по
    // userId) от тех, что у target были изначально, и второй запрос задел бы
    // чужие уведомления тоже.
    //
    // destination пересчитывается по каналу строки, только если у target есть
    // своя идентичность этого канала (телеграм / почта переданы параметрами).
    // Если её нет — destination остаётся прежним: доставится по старому
    // адресату, пока пользователь не привяжет канал на новой учётке, что
    // честнее, чем NOT NULL-столбец с выдуманным значением.
    //
    // WEB_PUSH сюда не попадает намеренно: подписка привязана к браузеру, а не
    // к идентичности, и слияние учёток её не переносит — destination (id
    // подписки) остаётся прежним по тому же ELSE.
    @Modifying
    @Query("""
            UPDATE ScheduledNotificationJpaEntity s
            SET s.userId = :to,
                s.destination = CASE
                    WHEN s.channel = ru.taskflow.notify.api.NotificationChannel.TELEGRAM AND :telegramDestination IS NOT NULL
                        THEN :telegramDestination
                    WHEN s.channel = ru.taskflow.notify.api.NotificationChannel.EMAIL AND :emailDestination IS NOT NULL
                        THEN :emailDestination
                    ELSE s.destination
                END
            WHERE s.userId = :from
            """)
    int reassignOwner(@Param("from") UUID from, @Param("to") UUID to,
                       @Param("telegramDestination") String telegramDestination,
                       @Param("emailDestination") String emailDestination);
}
