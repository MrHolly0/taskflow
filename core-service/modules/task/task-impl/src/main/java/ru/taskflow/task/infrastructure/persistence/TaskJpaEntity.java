package ru.taskflow.task.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "tasks")
@SQLRestriction("is_deleted = false")
@Getter
@Setter
public class TaskJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private GroupJpaEntity group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_task_id")
    private TaskJpaEntity parentTask;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.TODO;

    @Column(name = "deadline")
    private OffsetDateTime deadline;

    @Column(name = "estimate_minutes")
    private Integer estimateMinutes;

    @Column(name = "is_draft")
    private boolean isDraft = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskSource source = TaskSource.MANUAL;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_llm_response", columnDefinition = "jsonb")
    private String rawLlmResponse;

    @Column(name = "is_deleted")
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "task_tags",
            joinColumns = @JoinColumn(name = "task_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private List<TagJpaEntity> tags = new ArrayList<>();

    @PrePersist
    void prePersist() {
        var now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
