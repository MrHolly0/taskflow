package ru.taskflow.user.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
public class UserSettingsJpaEntity {

    @Id
    private UUID userId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "user_id")
    private UserJpaEntity user;

    @Column(name = "notifications_enabled")
    private boolean notificationsEnabled = true;

    @Column(name = "default_reminder_minutes")
    private int defaultReminderMinutes = 60;

    @Column(name = "urgent_extra_reminder")
    private boolean urgentExtraReminder = true;

    @Column(name = "preferred_llm", length = 32)
    private String preferredLlm = "groq";
}
