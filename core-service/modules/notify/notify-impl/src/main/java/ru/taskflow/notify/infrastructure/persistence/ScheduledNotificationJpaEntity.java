package ru.taskflow.notify.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "scheduled_notifications", indexes = {
    @Index(name = "idx_notif_fire_sent", columnList = "fire_at, sent")
})
@Getter
@Setter
public class ScheduledNotificationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    @Column(name = "fire_at", nullable = false)
    private OffsetDateTime fireAt;

    @Column(name = "payload_type", nullable = false, length = 32)
    private String payloadType;

    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private Boolean sent = false;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
