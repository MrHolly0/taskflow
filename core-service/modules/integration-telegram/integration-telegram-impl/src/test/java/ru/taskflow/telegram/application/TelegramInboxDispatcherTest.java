package ru.taskflow.telegram.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import ru.taskflow.telegram.infrastructure.client.TelegramRequestRetry;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUpdate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramInboxDispatcherTest {

    private final TelegramUpdateInbox inbox = mock(TelegramUpdateInbox.class);
    private final UpdateRouter router = mock(UpdateRouter.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    private final TelegramUpdateCheckpoint checkpoint = new TelegramUpdateCheckpoint(inbox);
    private final TelegramInboxDispatcher dispatcher = new TelegramInboxDispatcher(inbox, checkpoint, router,
            scheduler, new TelegramRequestRetry(new ObjectMapper()));

    @Test
    void successfulDeliveryCompletesAndStopsLeaseRenewal() {
        var update = claimed(1);
        dispatcher.dispatch();
        verify(router).route(update.update());
        verify(inbox).complete(update);
        verify(heartbeat).cancel(false);
        assertThat(checkpoint.requestKey()).isNull();
    }

    @Test
    void permanentFailureRemainsVisibleAndDoesNotLoop() {
        var update = claimed(1);
        doThrow(new IllegalArgumentException("invalid input")).when(router).route(any());
        dispatcher.dispatch();
        verify(inbox).fail(eq(update), eq(false), any(Duration.class), eq("IllegalArgumentException"));
        verify(inbox, never()).complete(any());
        verify(heartbeat).cancel(false);
    }

    @Test
    void lastAttemptStopsEvenForTemporaryNetworkFailure() {
        var update = claimed(6);
        doThrow(new ResourceAccessException("offline")).when(router).route(any());
        dispatcher.dispatch();
        verify(inbox).fail(eq(update), eq(false), any(Duration.class), eq("ResourceAccessException"));
    }

    @Test
    void telegramRetryAfterOverridesShortBackoff() {
        var update = claimed(1);
        var headers = new HttpHeaders();
        headers.set("Retry-After", "30");
        var failure = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "busy", headers, null, null);
        doThrow(failure).when(router).route(any());
        dispatcher.dispatch();
        var delay = ArgumentCaptor.forClass(Duration.class);
        verify(inbox).fail(eq(update), eq(true), delay.capture(), eq("TooManyRequests"));
        assertThat(delay.getValue()).isGreaterThanOrEqualTo(Duration.ofSeconds(30));
    }

    private TelegramUpdateInbox.ClaimedUpdate claimed(int attempt) {
        var update = new TelegramUpdateInbox.ClaimedUpdate(42, new TelegramUpdate(42, null, null),
                UUID.randomUUID(), attempt);
        when(inbox.claim()).thenReturn(Optional.of(update));
        doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofMinutes(1)));
        return update;
    }
}
