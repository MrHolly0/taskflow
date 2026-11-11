package ru.taskflow.user.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
public class UserSettingsJpaEntity {

    @Column(name = "telegram_widgets_enabled", nullable = false)
    private boolean telegramWidgetsEnabled = true;

    @Id
    private UUID userId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "user_id")
    private UserJpaEntity user;

    @Column(name = "notifications_enabled")
    private boolean notificationsEnabled = true;

    @Column(name = "notify_telegram")
    private boolean notifyTelegram = true;

    // Личный ящик Яндекса, лимит около 500 писем в сутки — по умолчанию
    // выключено для новых пользователей (решение владельца от 20.08.2026).
    // У существующих строк, где уже true, значение сохранится — эта
    // константа управляет только НОВЫМИ записями (see также миграция
    // 023-free-channels-by-default.yaml для DEFAULT самой колонки).
    @Column(name = "notify_email")
    private boolean notifyEmail = false;

    // "Включён" здесь может означать только "разрешение браузера выдано и
    // подписка жива" — то, что новая учётка ещё не может утверждать про
    // себя. Раньше это поле стартовало с true при разрешении, которое
    // запрашивается только по явному нажатию переключателя, — комбинация,
    // которой не бывает (см. также миграцию 026-push-off-by-default.yaml
    // для DEFAULT самой колонки).
    @Column(name = "notify_push")
    private boolean notifyPush = false;

    @Column(name = "default_reminder_minutes")
    private int defaultReminderMinutes = 60;

    @Column(name = "urgent_extra_reminder")
    private boolean urgentExtraReminder = true;

    @Column(name = "preferred_llm", length = 32)
    private String preferredLlm = "groq";

    @Column(name = "auto_clean_completed_days")
    private Integer autoCleanCompletedDays;

    @Column(name = "voice_input_mode_desktop", length = 16)
    private String voiceInputModeDesktop = "SILENCE";

    @Column(name = "voice_input_mode_mobile", length = 16)
    private String voiceInputModeMobile = "SILENCE";
    @Column(name = "task_checkins_enabled", nullable = false)
    private boolean taskCheckinsEnabled = true;

    @Column(name = "return_nudges_enabled", nullable = false)
    private boolean returnNudgesEnabled = true;

    @Column(name = "task_checkins_enabled_at")
    private OffsetDateTime taskCheckinsEnabledAt;

    @PrePersist
    void initializeEngagementDates() {
        var now=OffsetDateTime.now();
        if (taskCheckinsEnabled && taskCheckinsEnabledAt==null) taskCheckinsEnabledAt=now;
        if (returnNudgesEnabled && returnNudgesEnabledAt==null) returnNudgesEnabledAt=now;
    }

    @Column(name = "return_nudges_enabled_at")
    private OffsetDateTime returnNudgesEnabledAt;
}
