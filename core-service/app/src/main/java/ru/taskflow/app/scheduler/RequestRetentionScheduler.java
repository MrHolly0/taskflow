package ru.taskflow.app.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RequestRetentionScheduler {
    private final JdbcTemplate jdbc;
    private final ClusterJobRunner jobs;

    @Scheduled(cron = "0 30 4 * * *")
    public void clean() {
        jobs.run(910004L, () -> {
            jdbc.update("DELETE FROM task_creation_requests WHERE ctid IN (SELECT ctid FROM task_creation_requests WHERE created_at < NOW() - INTERVAL '30 days' LIMIT 5000)");
            jdbc.update("DELETE FROM assistant_request_results WHERE ctid IN (SELECT ctid FROM assistant_request_results WHERE created_at < NOW() - INTERVAL '30 days' LIMIT 5000)");
            jdbc.update("DELETE FROM scheduled_notifications WHERE id IN (SELECT id FROM scheduled_notifications WHERE sent=true AND sent_at < NOW() - INTERVAL '30 days' LIMIT 5000)");
        });
    }
}
