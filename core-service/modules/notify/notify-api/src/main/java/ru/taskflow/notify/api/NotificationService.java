package ru.taskflow.notify.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface NotificationService {
    void scheduleTaskReminder(UUID userId, UUID taskId, String title, OffsetDateTime deadline);
    void cancelTaskNotifications(UUID taskId);
}
