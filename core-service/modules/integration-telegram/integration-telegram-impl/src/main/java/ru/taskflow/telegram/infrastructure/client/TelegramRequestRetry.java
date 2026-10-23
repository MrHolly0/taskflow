package ru.taskflow.telegram.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class TelegramRequestRetry {

    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    @Autowired
    public TelegramRequestRetry(ObjectMapper objectMapper) {
        this(objectMapper, Thread::sleep);
    }

    TelegramRequestRetry(ObjectMapper objectMapper, Sleeper sleeper) {
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    public void execute(Runnable request) {
        for (int attempt = 0; ; attempt++) {
            try {
                request.run();
                return;
            } catch (RestClientException e) {
                if (attempt >= 2 || !transientFailure(e)) throw e;
                long backoff = ThreadLocalRandom.current().nextLong(500L << attempt, 1000L << attempt);
                long delay = Math.max(backoff, retryAfter(e).toMillis());
                // Длинный Retry-After оставляем очереди, не занимаем поток бота ожиданием.
                if (delay > 5000) throw e;
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public Duration retryAfter(Exception error) {
        if (!(error instanceof RestClientResponseException response)) return Duration.ZERO;
        long seconds = 0;
        String header = response.getResponseHeaders() == null ? null
                : response.getResponseHeaders().getFirst("Retry-After");
        if (header != null) {
            try {
                seconds = Long.parseLong(header);
            } catch (NumberFormatException e) {
                try {
                    seconds = Duration.between(Instant.now(),
                            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();
                } catch (RuntimeException ignored) {
                    // Telegram обычно передаёт секунды в JSON-поле parameters.retry_after.
                }
            }
        }
        try {
            long body = objectMapper.readTree(response.getResponseBodyAsByteArray())
                    .path("parameters").path("retry_after").asLong(0);
            seconds = Math.max(seconds, body);
        } catch (Exception ignored) {
            // На ошибках прокси JSON может отсутствовать.
        }
        return Duration.ofSeconds(Math.max(0, Math.min(86400, seconds)));
    }

    private boolean transientFailure(RestClientException error) {
        if (error instanceof ResourceAccessException) return true;
        return error instanceof RestClientResponseException response
                && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
