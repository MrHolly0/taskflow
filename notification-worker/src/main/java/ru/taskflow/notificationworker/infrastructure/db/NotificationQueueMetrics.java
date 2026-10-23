package ru.taskflow.notificationworker.infrastructure.db;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationQueueMetrics {

    private final JdbcTemplate jdbcTemplate;
    private volatile long ready;
    private volatile long failed;
    private volatile long leased;
    private volatile double oldestDueSeconds;

    public NotificationQueueMetrics(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder("taskflow.notifications.ready", this, metrics -> metrics.ready).register(registry);
        Gauge.builder("taskflow.notifications.failed", this, metrics -> metrics.failed).register(registry);
        Gauge.builder("taskflow.notifications.leased", this, metrics -> metrics.leased).register(registry);
        Gauge.builder("taskflow.notifications.oldest.due.seconds", this, metrics -> metrics.oldestDueSeconds)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.notification.metrics-interval-ms:30000}")
    public void refresh() {
        jdbcTemplate.query("""
                SELECT COUNT(*) FILTER (WHERE failed_at IS NULL AND next_attempt_at <= NOW() AND fire_at <= NOW()
                           AND (lease_until IS NULL OR lease_until <= NOW())) AS ready,
                       COUNT(*) FILTER (WHERE failed_at IS NOT NULL) AS failed,
                       COUNT(*) FILTER (WHERE lease_until > NOW()) AS leased,
                       COALESCE(EXTRACT(EPOCH FROM NOW() - MIN(fire_at)
                           FILTER (WHERE failed_at IS NULL AND fire_at <= NOW())), 0) AS oldest_due
                FROM scheduled_notifications WHERE sent = false
                """, result -> {
            ready = result.getLong("ready");
            failed = result.getLong("failed");
            leased = result.getLong("leased");
            oldestDueSeconds = result.getDouble("oldest_due");
        });
    }
}
