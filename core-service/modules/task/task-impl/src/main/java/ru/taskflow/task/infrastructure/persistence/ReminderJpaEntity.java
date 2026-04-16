package ru.taskflow.task.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "reminders")
@Getter
@Setter
public class ReminderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private TaskJpaEntity task;

    @Column(name = "offset_minutes", nullable = false)
    private int offsetMinutes;
}
