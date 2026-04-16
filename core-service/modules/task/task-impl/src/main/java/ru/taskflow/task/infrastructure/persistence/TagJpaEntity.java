package ru.taskflow.task.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "tags")
@Getter
@Setter
public class TagJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 16)
    private String color;
}
