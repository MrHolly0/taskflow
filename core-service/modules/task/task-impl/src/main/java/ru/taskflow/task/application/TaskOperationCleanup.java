package ru.taskflow.task.application;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public class TaskOperationCleanup {
    private final JdbcTemplate jdbc;
    public TaskOperationCleanup(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Scheduled(cron="0 40 2 * * *")
    public void prune() {
        jdbc.update("DELETE FROM task_operations WHERE id IN (SELECT id FROM task_operations WHERE expires_at<NOW()-INTERVAL '7 days' ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)");
    }
}
