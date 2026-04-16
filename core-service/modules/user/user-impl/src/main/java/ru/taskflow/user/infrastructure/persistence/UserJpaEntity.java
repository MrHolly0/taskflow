package ru.taskflow.user.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "telegram_id", nullable = false, unique = true)
    private Long telegramId;

    @Column(length = 64)
    private String username;

    @Column(name = "first_name", length = 128)
    private String firstName;

    @Column(name = "last_name", length = 128)
    private String lastName;

    @Column(name = "language_code", length = 8)
    private String languageCode = "ru";

    @Column(length = 64)
    private String timezone = "Europe/Moscow";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

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
