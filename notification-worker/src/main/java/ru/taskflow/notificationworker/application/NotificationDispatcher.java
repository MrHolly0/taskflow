package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.notificationworker.infrastructure.db.PendingNotification;
import ru.taskflow.notificationworker.infrastructure.db.ScheduledNotificationPoller;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDispatcher {

    private final ScheduledNotificationPoller poller;
    private final TelegramNotificationSender sender;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${app.notification.poll-interval-ms:30000}")
    @Transactional
    public void processNotifications() {
        List<PendingNotification> notifications = poller.pollPending();

        for (PendingNotification notification : notifications) {
            try {
                dispatchNotification(notification);
                poller.markAsSent(notification.id());
            } catch (Exception e) {
                log.error("Failed to dispatch notification {}: {}", notification.id(), e.getMessage());
                poller.incrementRetryCount(notification.id());
            }
        }
    }

    private void dispatchNotification(PendingNotification notification) {
        if ("TASK_REMINDER".equals(notification.payloadType())) {
            dispatchTaskReminder(notification);
        } else {
            log.warn("Unknown notification type: {}", notification.payloadType());
        }
    }

    private void dispatchTaskReminder(PendingNotification notification) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(notification.payload(), Map.class);
            String title = (String) payload.get("taskTitle");
            String deadline = (String) payload.get("deadline");

            sender.sendTaskReminder(notification.telegramChatId(), title, deadline);
        } catch (Exception e) {
            log.error("Failed to parse task reminder payload: {}", notification.payload(), e);
            throw new RuntimeException("Failed to dispatch task reminder", e);
        }
    }
}
