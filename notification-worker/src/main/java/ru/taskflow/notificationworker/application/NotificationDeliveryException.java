package ru.taskflow.notificationworker.application;

import java.time.Duration;

public class NotificationDeliveryException extends RuntimeException {

    private final boolean permanent;
    private final Duration retryAfter;

    public NotificationDeliveryException(String code, boolean permanent, Duration retryAfter) {
        super(code);
        this.permanent = permanent;
        this.retryAfter = retryAfter;
    }

    public boolean permanent() {
        return permanent;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
