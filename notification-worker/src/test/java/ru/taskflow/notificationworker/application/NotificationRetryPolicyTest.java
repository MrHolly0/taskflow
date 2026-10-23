package ru.taskflow.notificationworker.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRetryPolicyTest {

    private final NotificationRetryPolicy policy = new NotificationRetryPolicy();

    @Test
    void delayGrowsAndIsBoundedWithJitter() {
        var failure = new IOException("timeout");
        assertThat(policy.nextDelay(1, failure)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(policy.nextDelay(2, failure)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(20));
        assertThat(policy.nextDelay(20, failure)).isBetween(Duration.ofMinutes(30), Duration.ofHours(1));
        assertThat(policy.isPermanent(failure)).isFalse();
    }

    @Test
    void providerRetryAfterIsRespected() {
        var limited = new NotificationDeliveryException("HTTP_429", false, Duration.ofMinutes(5));
        assertThat(policy.nextDelay(1, limited)).isBetween(Duration.ofMinutes(5), Duration.ofMinutes(5).plusSeconds(1));
        assertThat(policy.isPermanent(limited)).isFalse();
        assertThat(policy.isPermanent(new NotificationDeliveryException("HTTP_403", true, null))).isTrue();
    }

    @Test
    void malformedRetryHeaderDoesNotPreventRetry() {
        assertThat(NotificationRetryPolicy.parseRetryAfter("120")).isEqualTo(Duration.ofMinutes(2));
        assertThat(NotificationRetryPolicy.parseRetryAfter("invalid")).isNull();
        assertThat(NotificationRetryPolicy.parseRetryAfter(null)).isNull();
        assertThat(NotificationRetryPolicy.parseRetryAfter("-1")).isEqualTo(Duration.ZERO);
    }
}
