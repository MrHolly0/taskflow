package ru.taskflow.user.api.dto;

public record UpdateSettingsRequest(
        Boolean notificationsEnabled,
        Boolean notifyTelegram,
        Boolean notifyEmail,
        Boolean notifyPush,
        Integer defaultReminderMinutes,
        Boolean urgentExtraReminder,
        String preferredLlm,
        Integer autoCleanCompletedDays,
        String voiceInputModeDesktop,
        String voiceInputModeMobile,
        String timezone,
        String displayName,
        Boolean taskCheckinsEnabled,
        Boolean returnNudgesEnabled,
        Boolean telegramWidgetsEnabled
) {
    public UpdateSettingsRequest(Boolean notificationsEnabled, Boolean notifyTelegram, Boolean notifyEmail,
            Boolean notifyPush, Integer defaultReminderMinutes, Boolean urgentExtraReminder,
            String preferredLlm, Integer autoCleanCompletedDays, String voiceInputModeDesktop,
            String voiceInputModeMobile, String timezone, String displayName, Boolean taskCheckinsEnabled,
            Boolean returnNudgesEnabled) {
        this(notificationsEnabled, notifyTelegram, notifyEmail, notifyPush, defaultReminderMinutes,
                urgentExtraReminder, preferredLlm, autoCleanCompletedDays, voiceInputModeDesktop,
                voiceInputModeMobile, timezone, displayName, taskCheckinsEnabled, returnNudgesEnabled, null);
    }
    public UpdateSettingsRequest(Boolean notificationsEnabled,
            Boolean notifyTelegram, Boolean notifyEmail, Boolean notifyPush,
            Integer defaultReminderMinutes, Boolean urgentExtraReminder,
            String preferredLlm, Integer autoCleanCompletedDays, String voiceInputModeDesktop,
            String voiceInputModeMobile, String timezone, String displayName) {
        this(notificationsEnabled, notifyTelegram, notifyEmail, notifyPush, defaultReminderMinutes,
                urgentExtraReminder, preferredLlm, autoCleanCompletedDays, voiceInputModeDesktop,
                voiceInputModeMobile, timezone, displayName, null, null);
    }
}
