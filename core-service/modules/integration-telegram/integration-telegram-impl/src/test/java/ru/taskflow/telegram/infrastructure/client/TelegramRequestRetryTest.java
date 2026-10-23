package ru.taskflow.telegram.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramRequestRetryTest {

    @Test
    void transientFailuresHaveBoundedGrowingRandomDelays() {
        var sleeps = new ArrayList<Long>();
        var retry = new TelegramRequestRetry(new ObjectMapper(), sleeps::add);
        AtomicInteger requests = new AtomicInteger();
        retry.execute(() -> {
            if (requests.incrementAndGet() < 3) throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
        });
        assertThat(requests.get()).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isBetween(500L, 999L);
        assertThat(sleeps.get(1)).isBetween(1000L, 1999L);
    }

    @Test
    void validationFailuresAreNeverRetried() {
        var sleeps = new ArrayList<Long>();
        var retry = new TelegramRequestRetry(new ObjectMapper(), sleeps::add);
        AtomicInteger requests = new AtomicInteger();
        assertThatThrownBy(() -> retry.execute(() -> {
            requests.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
        })).isInstanceOf(HttpClientErrorException.class);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void longRetryAfterIsReturnedToDurableQueueWithoutEarlyRetry() {
        var sleeps = new ArrayList<Long>();
        var retry = new TelegramRequestRetry(new ObjectMapper(), sleeps::add);
        var headers = new HttpHeaders();
        headers.add("Retry-After", "10");
        var error = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "limit", headers,
                "{\"parameters\":{\"retry_after\":30}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> retry.execute(() -> { throw error; })).isSameAs(error);
        assertThat(retry.retryAfter(error).toSeconds()).isEqualTo(30);
        assertThat(sleeps).isEmpty();
    }
}
