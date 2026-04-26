package ru.taskflow.audit.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "task_events", indexes = {
    @Index(name = "idx_events_task_occurred", columnList = "task_id, occurred_at DESC")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskEventJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID taskId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String eventType;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String delta;

    @Column(nullable = false)
    private OffsetDateTime occurredAt;

    @PrePersist
    void prePersist() {
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
