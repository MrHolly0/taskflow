package ru.taskflow.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import ru.taskflow.telegram.application.UpdateIdempotencyService;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateIdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private UpdateIdempotencyService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new UpdateIdempotencyService(redis);
    }

    @Test
    void newUpdateId_notYetProcessed() {
        when(valueOps.setIfAbsent(eq("tg:update:42"), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(service.isAlreadyProcessed(42L)).isFalse();
    }

    @Test
    void seenUpdateId_alreadyProcessed() {
        when(valueOps.setIfAbsent(eq("tg:update:42"), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service.isAlreadyProcessed(42L)).isTrue();
    }
}