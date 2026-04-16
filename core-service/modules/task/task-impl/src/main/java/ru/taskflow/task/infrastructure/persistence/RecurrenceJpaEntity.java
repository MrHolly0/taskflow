package ru.taskflow.task.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import ru.taskflow.task.api.RecurrenceType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recurrences")
@Getter
@Setter
public class RecurrenceJpaEntity {

    @Id
    private UUID taskId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "task_id")
    private TaskJpaEntity task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecurrenceType type;

    @Column(name = "interval_n")
    private int intervalN = 1;

    @Column(name = "days_of_week", length = 32)
    private String daysOfWeek;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;
}
