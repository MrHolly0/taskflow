package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Instant now = Instant.parse("2026-08-12T10:00:00Z");
    private final AssistantRateLimiter limiter = new AssistantRateLimiter(redis,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void countsAllChannelsInOneUserWindow() {
        UUID user = UUID.randomUUID();
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(10L, 11L);
        assertThat(limiter.allow(user)).isTrue();
        assertThat(limiter.allow(user)).isFalse();
        verify(redis, org.mockito.Mockito.times(2)).execute(any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of("assistant:rate:" + user + ":" + now.getEpochSecond() / 60)));
    }

    @Test
    void missingRedisResultDoesNotBypassBudget() {
        assertThat(limiter.allow(UUID.randomUUID())).isFalse();
    }
}
