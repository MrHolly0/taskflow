package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AssistantRateLimiter {

    private static final int LIMIT_PER_MINUTE = 10;
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 120) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final ru.taskflow.shared.resources.RedisResourceGate gate;
    private final ru.taskflow.shared.resources.ResourcePolicy policy;

    public ru.taskflow.shared.resources.RedisResourceGate.Lease enter(UUID user) {
        return gate.acquire("assistant",user,policy.getGlobalConcurrent(),policy.getUserConcurrent());
    }

    public boolean allow(UUID userId) {
        long bucket = Instant.now(clock).getEpochSecond() / 60;
        Long count = redis.execute(INCREMENT, List.of("assistant:rate:" + userId + ":" + bucket));
        return count != null && count <= LIMIT_PER_MINUTE;
    }
}
