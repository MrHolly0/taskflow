package ru.taskflow.task.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "groups")
@Getter
@Setter
public class GroupJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 16)
    private String color;

    @Column(length = 16)
    private String icon;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
