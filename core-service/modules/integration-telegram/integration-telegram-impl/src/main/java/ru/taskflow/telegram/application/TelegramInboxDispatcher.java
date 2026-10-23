package ru.taskflow.telegram.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.telegram.infrastructure.client.TelegramRequestRetry;
import ru.taskflow.shared.exception.RateLimitExceededException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Slf4j
public class TelegramInboxDispatcher {

    private final TelegramUpdateInbox inbox;
    private final TelegramUpdateCheckpoint checkpoint;
    private final UpdateRouter router;
    private final TaskScheduler scheduler;
    private final TelegramRequestRetry requestRetry;

    public TelegramInboxDispatcher(TelegramUpdateInbox inbox, TelegramUpdateCheckpoint checkpoint,
            UpdateRouter router, @Qualifier("telegramInboxScheduler") TaskScheduler scheduler,
            TelegramRequestRetry requestRetry) {
        this.inbox = inbox;
        this.checkpoint = checkpoint;
        this.router = router;
        this.scheduler = scheduler;
        this.requestRetry = requestRetry;
    }

    @Scheduled(fixedDelayString = "${app.telegram.inbox.poll-interval-ms:1000}", scheduler = "telegramInboxScheduler")
    public void dispatch() {
        inbox.claim().ifPresent(update -> {
            var heartbeat = scheduler.scheduleAtFixedRate(() -> inbox.renew(update), Duration.ofMinutes(1));
            try {
                checkpoint.within(update.id(), () -> router.route(update.update()));
                inbox.complete(update);
            } catch (Exception e) {
                boolean retry = update.attempt() < TelegramUpdateInbox.MAX_ATTEMPTS && retryable(e);
                Duration delay = retryDelay(update.attempt());
                Duration retryAfter = requestRetry.retryAfter(e);
                if (retryAfter.compareTo(delay) > 0) delay = retryAfter;
                inbox.fail(update, retry, delay, e.getClass().getSimpleName());
                log.warn("Telegram update {}: {}, retry={}", update.id(), e.getClass().getSimpleName(), retry);
            } finally {
                if (heartbeat != null) heartbeat.cancel(false);
            }
        });
    }

    @Scheduled(cron = "0 15 * * * *", scheduler = "telegramInboxScheduler")
    public void prune() {
        inbox.prune();
    }

    private boolean retryable(Exception e) {
        if (e instanceof RestClientResponseException response) {
            return response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429;
        }
        if (e instanceof ResponseStatusException response) {
            return response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429;
        }
        return e instanceof RateLimitExceededException || e instanceof ResourceAccessException
                || e instanceof TransientDataAccessException
                || e instanceof DataAccessResourceFailureException || e instanceof RecoverableDataAccessException;
    }

    static Duration retryDelay(int attempt) {
        long seconds = Math.min(300, 5L << Math.min(6, Math.max(0, attempt - 1)));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(seconds * 500, seconds * 1000 + 1));
    }
}
