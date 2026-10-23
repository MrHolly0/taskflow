package ru.taskflow.telegram.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TelegramInboxMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot = new Snapshot(0, 0, 0);

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("telegram.inbox.pending", this, metrics -> metrics.snapshot.pending()).register(registry);
        Gauge.builder("telegram.inbox.failed", this, metrics -> metrics.snapshot.failed()).register(registry);
        Gauge.builder("telegram.inbox.oldest.age", this, metrics -> metrics.snapshot.oldestAgeSeconds())
                .baseUnit("seconds").register(registry);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000, scheduler = "telegramInboxScheduler")
    public void refresh() {
        Snapshot updated = jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status IN ('PENDING', 'PROCESSING')) AS pending,
                    COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                    COALESCE(EXTRACT(EPOCH FROM NOW() - MIN(received_at)
                        FILTER (WHERE status IN ('PENDING', 'PROCESSING'))), 0) AS oldest_age
                FROM telegram_update_inbox WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
                """, (rs, row) -> new Snapshot(rs.getLong("pending"), rs.getLong("failed"),
                Math.max(0, rs.getDouble("oldest_age"))));
        if (updated != null) snapshot = updated;
    }

    private record Snapshot(long pending, long failed, double oldestAgeSeconds) {}
}
