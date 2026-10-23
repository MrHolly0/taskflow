package ru.taskflow.notificationworker.infrastructure.db;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScheduledNotificationPoller {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.notification.max-retries:5}")
    private int maxAttempts;

    @Value("${app.notification.lease-seconds:120}")
    private int leaseSeconds;

    @Transactional
    public List<PendingNotification> claimPending(int limit) {
        jdbcTemplate.update("""
                UPDATE scheduled_notifications
                SET failed_at = NOW(), last_error = 'ATTEMPTS_EXHAUSTED', lease_token = NULL, lease_until = NULL
                WHERE sent = false AND failed_at IS NULL AND retry_count >= ?
                  AND (lease_until IS NULL OR lease_until <= NOW())
                """, maxAttempts);
        UUID token = UUID.randomUUID();
        return jdbcTemplate.query("""
                WITH ready AS (
                    SELECT id FROM scheduled_notifications
                    WHERE sent = false AND failed_at IS NULL AND fire_at <= NOW()
                      AND next_attempt_at <= NOW() AND retry_count < ?
                      AND (lease_until IS NULL OR lease_until <= NOW())
                    ORDER BY next_attempt_at, fire_at, id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE scheduled_notifications n
                SET lease_token = ?, lease_until = NOW() + (? * INTERVAL '1 second'),
                    retry_count = retry_count + 1
                FROM ready WHERE n.id = ready.id
                RETURNING n.id, n.channel, n.destination, n.payload_type, n.payload, n.lease_token, n.retry_count
                """, (rs, row) -> new PendingNotification(
                rs.getObject("id", UUID.class), rs.getString("channel"), rs.getString("destination"),
                rs.getString("payload_type"), rs.getString("payload"),
                rs.getObject("lease_token", UUID.class), rs.getInt("retry_count")),
                maxAttempts, limit, token, Math.max(60, leaseSeconds));
    }

    @Transactional(readOnly = true)
    public boolean isClaimActive(PendingNotification notification) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM scheduled_notifications
                    WHERE id = ? AND lease_token = ? AND lease_until > NOW()
                      AND sent = false AND failed_at IS NULL)
                """, Boolean.class, notification.id(), notification.leaseToken()));
    }

    @Transactional
    public void markAsSent(PendingNotification notification) {
        jdbcTemplate.update("""
                UPDATE scheduled_notifications SET sent = true, sent_at = NOW(),
                    lease_token = NULL, lease_until = NULL, last_error = NULL
                WHERE id = ? AND lease_token = ? AND sent = false AND failed_at IS NULL
                """, notification.id(), notification.leaseToken());
    }

    @Transactional
    public void recordFailure(PendingNotification notification, boolean permanent, Duration delay, String errorCode) {
        boolean failed = permanent || notification.attempt() >= maxAttempts;
        jdbcTemplate.update("""
                UPDATE scheduled_notifications SET next_attempt_at = NOW() + (? * INTERVAL '1 millisecond'),
                    failed_at = CASE WHEN ? THEN NOW() ELSE NULL END,
                    last_error = ?, lease_token = NULL, lease_until = NULL
                WHERE id = ? AND lease_token = ? AND sent = false AND failed_at IS NULL
                """, delay.toMillis(), failed, errorCode,
                notification.id(), notification.leaseToken());
    }
}
