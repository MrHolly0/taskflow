package ru.taskflow.user.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис управления refresh-токенами.
 *
 * Выдаёт долгоживущие refresh-токены для обновления JWT,
 * хранит их в Redis с TTL 30 дней.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Duration TTL = Duration.ofDays(30);
    private static final String PREFIX = "refresh:";

    private final StringRedisTemplate redis;

    public String issue(UUID userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + token, userId.toString(), TTL);
        return token;
    }

    public Optional<UUID> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String value = redis.opsForValue().get(PREFIX + token);
        if (value == null) return Optional.empty();
        return Optional.of(UUID.fromString(value));
    }

    public void revoke(String token) {
        redis.delete(PREFIX + token);
    }
}