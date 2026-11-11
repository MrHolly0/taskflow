package ru.taskflow.notify.application;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.user.api.QuietHoursChanged;
import java.time.Clock;
import java.time.OffsetDateTime;
@Component
@RequiredArgsConstructor
public class QuietHoursRescheduler {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @EventListener @Transactional
    public void changed(QuietHoursChanged event) {
        var now=OffsetDateTime.now(clock);
        jdbc.update("""
                UPDATE scheduled_notifications SET next_attempt_at=?,quiet_deferred_until=NULL
                WHERE user_id=? AND NOT sent AND failed_at IS NULL AND engagement_prompt_id IS NOT NULL
                  AND quiet_deferred_until IS NOT NULL AND next_attempt_at=quiet_deferred_until
                  AND (lease_until IS NULL OR lease_until<=?)
                """,now,event.userId(),now);
    }
}
