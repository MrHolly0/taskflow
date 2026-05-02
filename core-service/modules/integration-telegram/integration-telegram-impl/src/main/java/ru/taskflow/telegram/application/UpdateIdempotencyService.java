package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Сервис дедупликации обновлений от Telegram.
 *
 * Предотвращает обработку одного и того же обновления дважды,
 * используя Redis для кэширования уже обработанных update_id.
 */
@Service
@RequiredArgsConstructor
public class UpdateIdempotencyService {

    private final StringRedisTemplate redis;

    public boolean isAlreadyProcessed(long updateId) {
        Boolean inserted = redis.opsForValue()
                .setIfAbsent("tg:update:" + updateId, "1", Duration.ofHours(1));
        return !Boolean.TRUE.equals(inserted);
    }
}