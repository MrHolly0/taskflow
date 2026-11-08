package ru.taskflow.app.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AssistantRequestMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong expired=new AtomicLong();
    public AssistantRequestMetrics(JdbcTemplate jdbc,MeterRegistry meters) {
        this.jdbc=jdbc;meters.gauge("assistant_request_expired_claims",expired);
    }
    @Scheduled(fixedDelay=60_000,initialDelay=60_000)
    public void refresh() {
        Long count=jdbc.queryForObject("""
                SELECT COUNT(*) FROM assistant_request_results
                WHERE response_json IS NULL AND lease_until IS NOT NULL AND lease_until < CURRENT_TIMESTAMP
                """,Long.class);
        expired.set(count==null?0:count);
    }
}
