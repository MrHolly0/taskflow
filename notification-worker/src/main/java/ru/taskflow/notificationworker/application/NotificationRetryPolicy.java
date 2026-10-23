package ru.taskflow.notificationworker.application;

import jakarta.mail.SendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class NotificationRetryPolicy {

    public boolean isPermanent(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof NotificationDeliveryException delivery) {
                return delivery.permanent();
            }
            if (cause instanceof MailAuthenticationException || cause instanceof MailParseException
                    || cause instanceof IllegalArgumentException) {
                return true;
            }
            if (cause instanceof SendFailedException send && send.getInvalidAddresses() != null
                    && send.getInvalidAddresses().length > 0) {
                return true;
            }
        }
        return false;
    }

    public Duration nextDelay(int attempt, Throwable failure) {
        long ceilingSeconds = Math.min(3600, 10L << Math.clamp(attempt - 1, 0, 10));
        Duration delay = Duration.ofMillis(ThreadLocalRandom.current().nextLong(
                ceilingSeconds * 500, ceilingSeconds * 1000 + 1));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof NotificationDeliveryException delivery && delivery.retryAfter() != null
                    && delivery.retryAfter().compareTo(delay) > 0) {
                return delivery.retryAfter().plusMillis(ThreadLocalRandom.current().nextLong(1000));
            }
        }
        return delay;
    }

    public static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return Duration.ofSeconds(Math.clamp(seconds, 0, 604800));
        } catch (NumberFormatException ignored) {
            try {
                Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.ofSeconds(Math.clamp(Duration.between(Instant.now(), date).getSeconds(), 0, 604800));
            } catch (RuntimeException invalidDate) {
                return null;
            }
        }
    }
}
