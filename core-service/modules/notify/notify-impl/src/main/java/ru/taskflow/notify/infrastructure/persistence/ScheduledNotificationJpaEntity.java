package ru.taskflow.notify.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.taskflow.notify.api.NotificationChannel;

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

    @Column(name = "task_id")
    private UUID taskId;

    // Nullable: строки, созданные до введения этого столбца, его не несут —
    // им отмена по reminderId просто не сможет адресоваться, что не хуже
    // прежнего поведения. Все новые строки клеймятся всегда (см. scheduleReminder).
    @Column(name = "reminder_id")
    private UUID reminderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(nullable = false, length = 320)
    private String destination;

    @Column(name = "fire_at", nullable = false)
    private OffsetDateTime fireAt;

    @Column(name = "payload_type", nullable = false, length = 32)
    private String payloadType;

    @JdbcTypeCode(SqlTypes.JSON)
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
