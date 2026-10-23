package ru.taskflow.task.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.DynamicUpdate;
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
@DynamicUpdate
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

    // День исполнения — предположение, когда заняться, в отличие от deadline
    // (обязательство). Двигает «Отложить», не участвует в просрочке.
    @Column(name = "planned_date")
    private OffsetDateTime plannedDate;

    // Отдельно от updatedAt — тот меняется при любой правке, а не только при
    // переходе в работу или переносе дня (нужно для счётчика продвижения).
    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "planned_date_set_at")
    private OffsetDateTime plannedDateSetAt;

    @Column(name = "planned_date_suggestion_attempted_at")
    private OffsetDateTime plannedDateSuggestionAttemptedAt;

    // Момент выдачи задачи в фокус (не действия над ней) — единственное
    // основание для подъёма давно не показанной бессрочной задачи. Не
    // отображается пользователю ни в каком виде (А3).
    @Column(name = "last_shown_in_focus_at")
    private OffsetDateTime lastShownInFocusAt;

    @Column(name = "first_step_hint", length = 200)
    private String firstStepHint;

    // Ненулевая отметка означает, что гипотеза §5.1 уже вычислялась, даже
    // если модель не вернула пригодный текст: повторно её не вызываем.
    @Column(name = "first_step_generated_at")
    private OffsetDateTime firstStepGeneratedAt;

    // Настойчивость по задаче (Б1) — ставит человек, в карточке задачи или
    // предложения; модель это поле не предлагает и не читает.
    @Column(name = "persistent_reminder", nullable = false)
    private boolean persistentReminder = false;

    @Column(name = "estimate_minutes")
    private Integer estimateMinutes;

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

    @Column(name = "recurrence_advanced", nullable = false)
    private boolean recurrenceAdvanced;

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
