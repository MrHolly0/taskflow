package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.taskflow.notificationworker.infrastructure.db.PendingNotification;
import ru.taskflow.notificationworker.infrastructure.db.ScheduledNotificationPoller;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDispatcher {

    private final ScheduledNotificationPoller poller;
    private final List<NotificationSender> senders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService notificationExecutor;
    private final NotificationRetryPolicy retryPolicy;

    @Value("${app.notification.batch-size:50}")
    private int batchSize;

    @Value("${app.notification.concurrency:4}")
    private int concurrency;

    @Value("${app.telegram.widgets-enabled:false}")
    private boolean widgetsEnabled;

    @Scheduled(fixedDelayString = "${app.notification.poll-interval-ms:5000}")
    public void processNotifications() {
        int remaining = Math.clamp(batchSize, 1, 500);
        while (remaining > 0) {
            int limit = Math.min(remaining, Math.clamp(concurrency, 1, 16));
            List<PendingNotification> notifications = poller.claimPending(limit);
            if (notifications.isEmpty()) {
                return;
            }
            // Не держим в очереди исполнителя задания с уже отсчитываемой арендой.
            CompletableFuture<?>[] deliveries = notifications.stream()
                    .map(notification -> CompletableFuture.runAsync(() -> deliver(notification), notificationExecutor))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(deliveries).join();
            remaining -= notifications.size();
        }
    }

    private void deliver(PendingNotification notification) {
        try {
            if (!poller.isClaimActive(notification)) {
                return;
            }
            try {
                if (!dispatchNotification(notification)) return;
            } catch (PushSubscriptionGoneException gone) {
                jdbcTemplate.update("DELETE FROM push_subscriptions WHERE id = ?",
                        UUID.fromString(notification.destination()));
                poller.recordFailure(notification, true, Duration.ZERO, "PUSH_SUBSCRIPTION_GONE");
                return;
            } catch (Exception failure) {
                boolean permanent = retryPolicy.isPermanent(failure);
                String code = failure instanceof NotificationDeliveryException ? failure.getMessage()
                        : failure.getClass().getSimpleName();
                poller.recordFailure(notification, permanent, retryPolicy.nextDelay(notification.attempt(), failure), code);
                log.warn("Notification {} attempt {} failed: {}, permanent={}",
                        notification.id(), notification.attempt(), code, permanent);
                return;
            }
            // Ошибка фиксации после отправки не является ошибкой доставки. После истечения
            // аренды возможен повтор: внешние каналы не поддерживают общую транзакцию с БД.
            poller.markAsSent(notification);
        } catch (Exception databaseFailure) {
            log.error("Could not finish notification {}: {}", notification.id(),
                    databaseFailure.getClass().getSimpleName());
        }
    }

    private boolean dispatchNotification(PendingNotification notification) {
        if ("WIDGET_SYNC".equals(notification.payloadType())) {
            senders.stream().filter(sender->sender.supports("TELEGRAM")).findFirst().orElseThrow()
                    .sendWidgetSync(notification);
            return true;
        }
        if ("CHECKIN_BATCH".equals(notification.payloadType()) || "WORK_CHECKIN".equals(notification.payloadType()) || "TASK_CHECKIN".equals(notification.payloadType()) || "RETURN_NUDGE".equals(notification.payloadType())) {
            return dispatchEngagement(notification);
        }
        if (!"TASK_REMINDER".equals(notification.payloadType())) {
            throw new NotificationDeliveryException("UNKNOWN_PAYLOAD_TYPE", true, null);
        }
        if(poller.coalesceOverdueReminder(notification)) return false;
        NotificationSender sender = senders.stream()
                .filter(candidate -> candidate.supports(notification.channel()))
                .findFirst()
                .orElseThrow(() -> new NotificationDeliveryException("UNSUPPORTED_CHANNEL", true, null));
        ReminderPayload payload;
        try {
            payload = objectMapper.readValue(notification.payload(), ReminderPayload.class);
            if (payload.taskTitle() == null || payload.taskTitle().isBlank()) {
                throw new IllegalArgumentException("Missing title");
            }
        } catch (Exception invalidPayload) {
            throw new NotificationDeliveryException("INVALID_PAYLOAD", true, null);
        }
        if (widgetsEnabled && "TELEGRAM".equals(notification.channel())) sender.sendTaskReminderCard(notification,payload.taskTitle(),payload.deadline(),payload.timezone());
        else sender.sendTaskReminder(notification.destination(), payload.taskTitle(), payload.deadline(), payload.timezone());
        return true;
    }

    private boolean dispatchEngagement(PendingNotification notification) {
        EngagementPayload payload;
        try {
            payload = objectMapper.readValue(notification.payload(), EngagementPayload.class);
            if (payload.promptId() == null || !"TELEGRAM".equals(notification.channel()))
                throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new NotificationDeliveryException("INVALID_ENGAGEMENT_PAYLOAD", true, null);
        }
        var gate = poller.checkEngagement(notification, payload.promptId());
        if (!gate.valid()) {
            poller.cancelEngagement(notification);
            return false;
        }
        if (gate.deferUntil() != null) {
            poller.deferEngagement(notification, gate.deferUntil());
            return false;
        }
        var current = new EngagementPayload(payload.promptId(), payload.taskId(), gate.title(), payload.deadline(),
                gate.timezone(), payload.appUrl());
        var sender=senders.stream().filter(candidate -> candidate.supports("TELEGRAM")).findFirst()
                .orElseThrow(() -> new NotificationDeliveryException("UNSUPPORTED_CHANNEL",true,null));
        if (widgetsEnabled || "CHECKIN_BATCH".equals(notification.payloadType())) sender.sendEngagement(notification,current);
        else sender.sendEngagement(notification.destination(),notification.payloadType(),current);
        return true;
    }

    private record ReminderPayload(String taskTitle, String deadline, String timezone) {}
}
