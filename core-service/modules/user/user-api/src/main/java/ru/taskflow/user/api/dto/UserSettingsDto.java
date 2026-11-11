package ru.taskflow.user.api.dto;

public record UserSettingsDto(
        boolean notificationsEnabled,
        boolean notifyTelegram,
        boolean notifyEmail,
        boolean notifyPush,
        int defaultReminderMinutes,
        boolean urgentExtraReminder,
        String preferredLlm,
        Integer autoCleanCompletedDays,
        String voiceInputModeDesktop,
        String voiceInputModeMobile,
        String timezone,
        String displayName,
        boolean taskCheckinsEnabled,
        boolean returnNudgesEnabled,
        boolean telegramWidgetsEnabled
) {
    public UserSettingsDto(boolean notificationsEnabled, boolean notifyTelegram, boolean notifyEmail,
            boolean notifyPush, int defaultReminderMinutes, boolean urgentExtraReminder,
            String preferredLlm, Integer autoCleanCompletedDays, String voiceInputModeDesktop,
            String voiceInputModeMobile, String timezone, String displayName, boolean taskCheckinsEnabled,
            boolean returnNudgesEnabled) {
        this(notificationsEnabled, notifyTelegram, notifyEmail, notifyPush, defaultReminderMinutes,
                urgentExtraReminder, preferredLlm, autoCleanCompletedDays, voiceInputModeDesktop,
                voiceInputModeMobile, timezone, displayName, taskCheckinsEnabled, returnNudgesEnabled, true);
    }
    public UserSettingsDto(boolean notificationsEnabled,
            boolean notifyTelegram, boolean notifyEmail, boolean notifyPush,
            int defaultReminderMinutes, boolean urgentExtraReminder,
            String preferredLlm, Integer autoCleanCompletedDays, String voiceInputModeDesktop,
            String voiceInputModeMobile, String timezone, String displayName) {
        this(notificationsEnabled, notifyTelegram, notifyEmail, notifyPush, defaultReminderMinutes,
                urgentExtraReminder, preferredLlm, autoCleanCompletedDays, voiceInputModeDesktop,
                voiceInputModeMobile, timezone, displayName, false, false);
    }
}
