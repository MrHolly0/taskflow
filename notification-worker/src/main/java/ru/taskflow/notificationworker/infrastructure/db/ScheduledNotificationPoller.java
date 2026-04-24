package ru.taskflow.notificationworker.infrastructure.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledNotificationPoller {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.notification.batch-size:50}")
    private int batchSize;

    @Value("${app.notification.max-retries:3}")
    private int maxRetries;

    @Transactional
    public List<PendingNotification> pollPending() {
        String sql = """
            SELECT id, telegram_chat_id, payload_type, payload
            FROM scheduled_notifications
            WHERE fire_at <= NOW() AND sent = false AND retry_count < ?
            ORDER BY fire_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

        RowMapper<PendingNotification> rowMapper = (rs, rowNum) ->
            new PendingNotification(
                UUID.fromString(rs.getString("id")),
                rs.getLong("telegram_chat_id"),
                rs.getString("payload_type"),
                rs.getString("payload")
            );

        List<PendingNotification> notifications = jdbcTemplate.query(sql, rowMapper, maxRetries, batchSize);
        log.debug("Polled {} pending notifications", notifications.size());
        return notifications;
    }

    public void markAsSent(UUID notificationId) {
        String sql = "UPDATE scheduled_notifications SET sent = true, sent_at = NOW() WHERE id = ?";
        int updated = jdbcTemplate.update(sql, notificationId.toString());
        if (updated > 0) {
            log.debug("Marked notification {} as sent", notificationId);
        }
    }

    public void incrementRetryCount(UUID notificationId) {
        String sql = "UPDATE scheduled_notifications SET retry_count = retry_count + 1 WHERE id = ?";
        int updated = jdbcTemplate.update(sql, notificationId.toString());
        if (updated > 0) {
            log.debug("Incremented retry count for notification {}", notificationId);
        }
    }
}
