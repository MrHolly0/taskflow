package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.taskflow.notificationworker.infrastructure.db.PendingNotification;
import ru.taskflow.notificationworker.infrastructure.db.ScheduledNotificationPoller;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDispatcherTest {

    private final ScheduledNotificationPoller poller = mock(ScheduledNotificationPoller.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NotificationDispatcher dispatcher = new NotificationDispatcher(poller, List.of(sender),
            new ObjectMapper(), jdbcTemplate, new DirectExecutor(), new NotificationRetryPolicy());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dispatcher, "batchSize", 4);
        ReflectionTestUtils.setField(dispatcher, "concurrency", 4);
        when(poller.isClaimActive(any())).thenReturn(true);
        when(sender.supports("TELEGRAM")).thenReturn(true);
        when(sender.supports("EMAIL")).thenReturn(true);
        when(sender.supports("WEB_PUSH")).thenReturn(true);
    }

    @Test
    void successAcknowledgesTheOwnedLease() {
        var row = pending("TELEGRAM", "12345", "TASK_REMINDER", validPayload());
        when(poller.claimPending(4)).thenReturn(List.of(row));

        dispatcher.processNotifications();

        verify(sender).sendTaskReminder("12345", "купить молоко", null, "Europe/Moscow");
        verify(poller).markAsSent(row);
        verify(poller, never()).recordFailure(any(), anyBoolean(), any(), any());
    }

    @Test
    void deliveryFailureDoesNotBlockAnotherChannel() {
        var telegram = pending("TELEGRAM", "12345", "TASK_REMINDER", validPayload());
        var email = pending("EMAIL", "user@example.com", "TASK_REMINDER", validPayload());
        when(poller.claimPending(4)).thenReturn(List.of(telegram, email));
        doThrow(new NotificationDeliveryException("TELEGRAM_HTTP_403", true, null))
                .when(sender).sendTaskReminder(eq("12345"), any(), any(), any());

        dispatcher.processNotifications();

        verify(poller).recordFailure(eq(telegram), eq(true), any(Duration.class), eq("TELEGRAM_HTTP_403"));
        verify(poller).markAsSent(email);
    }

    @Test
    void gonePushSubscriptionIsDeletedAndMarkedAsTerminalFailure() {
        UUID subscription = UUID.randomUUID();
        var row = pending("WEB_PUSH", subscription.toString(), "TASK_REMINDER", validPayload());
        when(poller.claimPending(4)).thenReturn(List.of(row));
        doThrow(new PushSubscriptionGoneException(subscription.toString()))
                .when(sender).sendTaskReminder(any(), any(), any(), any());

        dispatcher.processNotifications();

        verify(jdbcTemplate).update("DELETE FROM push_subscriptions WHERE id = ?", subscription);
        verify(poller).recordFailure(row, true, Duration.ZERO, "PUSH_SUBSCRIPTION_GONE");
        verify(poller, never()).markAsSent(row);
    }

    @Test
    void malformedPayloadIsNotRetried() {
        var row = pending("TELEGRAM", "12345", "TASK_REMINDER", "не json");
        when(poller.claimPending(4)).thenReturn(List.of(row));

        dispatcher.processNotifications();

        verify(poller).recordFailure(eq(row), eq(true), any(Duration.class), eq("INVALID_PAYLOAD"));
        verify(sender, never()).sendTaskReminder(any(), any(), any(), any());
    }

    @Test
    void unknownPayloadIsNotSilentlyAcknowledged() {
        var row = pending("TELEGRAM", "12345", "OTHER", validPayload());
        when(poller.claimPending(4)).thenReturn(List.of(row));

        dispatcher.processNotifications();

        verify(poller).recordFailure(eq(row), eq(true), any(Duration.class), eq("UNKNOWN_PAYLOAD_TYPE"));
        verify(poller, never()).markAsSent(row);
    }

    @Test
    void cancelledOrExpiredClaimDoesNotSend() {
        var row = pending("TELEGRAM", "12345", "TASK_REMINDER", validPayload());
        when(poller.claimPending(4)).thenReturn(List.of(row));
        when(poller.isClaimActive(row)).thenReturn(false);

        dispatcher.processNotifications();

        verify(sender, never()).sendTaskReminder(any(), any(), any(), any());
        verify(poller, never()).markAsSent(row);
    }

    @Test
    void failedAcknowledgementDoesNotReclassifySuccessfulDelivery() {
        var row = pending("TELEGRAM", "12345", "TASK_REMINDER", validPayload());
        when(poller.claimPending(4)).thenReturn(List.of(row));
        doThrow(new IllegalStateException("database offline")).when(poller).markAsSent(row);

        dispatcher.processNotifications();

        verify(poller, never()).recordFailure(any(), anyBoolean(), any(), any());
    }

    private PendingNotification pending(String channel, String destination, String type, String payload) {
        return new PendingNotification(UUID.randomUUID(), channel, destination, type, payload, UUID.randomUUID(), 1);
    }

    private String validPayload() {
        return "{\"taskTitle\":\"купить молоко\",\"deadline\":null,\"timezone\":\"Europe/Moscow\"}";
    }

    private static final class DirectExecutor extends AbstractExecutorService {
        private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
        @Override public void execute(Runnable command) { command.run(); }
    }
}
