package ru.taskflow.notificationworker.infrastructure.db;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.LocalTime;
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
                      AND sent = false AND failed_at IS NULL AND channel=? AND destination=?)
                """, Boolean.class, notification.id(), notification.leaseToken(),notification.channel(),notification.destination()));
    }

    @Transactional
    public boolean coalesceOverdueReminder(PendingNotification notification) {
        if(!"TELEGRAM".equals(notification.channel()) || !"TASK_REMINDER".equals(notification.payloadType())) return false;
        var rows=jdbcTemplate.query("""
                SELECT n.user_id, EXISTS(SELECT 1 FROM engagement_prompts p WHERE p.user_id=n.user_id AND p.task_id=t.id
                  AND p.kind IN ('TASK_CHECKIN','WORK_CHECKIN') AND p.deadline_snapshot IS NOT DISTINCT FROM t.deadline
                  AND p.cancelled_at IS NULL AND p.sent_at>=n.fire_at) AS covered
                FROM scheduled_notifications n JOIN tasks t ON t.id=n.task_id AND t.user_id=n.user_id
                JOIN user_settings s ON s.user_id=n.user_id
                WHERE n.id=? AND n.lease_token=? AND n.lease_until>NOW() AND NOT n.sent AND n.failed_at IS NULL
                  AND n.channel=? AND n.destination=? AND n.payload_type='TASK_REMINDER'
                  AND n.fire_at<=NOW()-INTERVAL '30 minutes' AND t.deadline<=NOW()-INTERVAL '30 minutes'
                  AND NOT t.is_deleted AND t.status IN ('TODO','IN_PROGRESS')
                  AND s.notifications_enabled AND s.notify_telegram AND s.task_checkins_enabled
                  AND NOT EXISTS(SELECT 1 FROM engagement_prompts prior WHERE prior.user_id=n.user_id AND prior.task_id=t.id
                    AND prior.kind IN ('TASK_CHECKIN','WORK_CHECKIN') AND prior.deadline_snapshot IS NOT DISTINCT FROM t.deadline
                    AND prior.cancelled_at IS NULL AND prior.sent_at<n.fire_at)
                """,(rs,row)->new OverdueReminder(rs.getObject("user_id",UUID.class),rs.getBoolean("covered")),
                notification.id(),notification.leaseToken(),notification.channel(),notification.destination());
        if(rows.isEmpty()) return false;
        var reminder=rows.getFirst();
        if(reminder.covered()) jdbcTemplate.update("""
                DELETE FROM scheduled_notifications WHERE id=? AND user_id=? AND lease_token=? AND lease_until>NOW()
                  AND NOT sent AND failed_at IS NULL AND channel=? AND destination=?
                """,notification.id(),reminder.user(),notification.leaseToken(),notification.channel(),notification.destination());
        else jdbcTemplate.update("""
                UPDATE scheduled_notifications SET next_attempt_at=NOW()+INTERVAL '90 seconds',lease_token=NULL,lease_until=NULL,
                  retry_count=GREATEST(0,retry_count-1)
                WHERE id=? AND user_id=? AND lease_token=? AND lease_until>NOW() AND NOT sent AND failed_at IS NULL AND channel=? AND destination=?
                """,notification.id(),reminder.user(),notification.leaseToken(),notification.channel(),notification.destination());
        return true;
    }
    private record OverdueReminder(UUID user,boolean covered) {}

    @Transactional
    public void markAsSent(PendingNotification notification) {
        if ("WIDGET_SYNC".equals(notification.payloadType())) {
            jdbcTemplate.update("""
                    UPDATE scheduled_notifications SET sent=(widget_revision=(?::jsonb->>'revision')::bigint),
                      sent_at=CASE WHEN widget_revision=(?::jsonb->>'revision')::bigint THEN NOW() ELSE NULL END,
                      next_attempt_at=NOW(),retry_count=CASE WHEN widget_revision=(?::jsonb->>'revision')::bigint THEN retry_count ELSE 0 END,
                      lease_token=NULL,lease_until=NULL,last_error=NULL
                    WHERE id=? AND lease_token=? AND NOT sent AND failed_at IS NULL AND channel=? AND destination=?
                    """,notification.payload(),notification.payload(),notification.payload(),notification.id(),notification.leaseToken(),notification.channel(),notification.destination());
            return;
        }
        if (isEngagement(notification)) {
            // Отмена при слиянии учёток берёт журнал перед очередью; сохраняем тот же порядок.
            var locked = jdbcTemplate.query("""
                    SELECT p.id FROM engagement_prompts p
                    JOIN scheduled_notifications n ON n.engagement_prompt_id = p.id
                    WHERE n.id = ? AND n.lease_token = ? AND NOT n.sent AND n.failed_at IS NULL
                    FOR UPDATE OF p
                    """, (rs, row) -> rs.getObject("id", UUID.class), notification.id(), notification.leaseToken());
            if (locked.isEmpty()) return;
        }
        int updated = jdbcTemplate.update("""
                UPDATE scheduled_notifications SET sent = true, sent_at = NOW(),
                    lease_token = NULL, lease_until = NULL, last_error = NULL
                WHERE id = ? AND lease_token = ? AND sent = false AND failed_at IS NULL AND channel=? AND destination=?
                """, notification.id(), notification.leaseToken(),notification.channel(),notification.destination());
        if (updated == 1 && isEngagement(notification)) {
            jdbcTemplate.update("""
                    UPDATE engagement_prompts SET sent_at = NOW()
                    WHERE id = (SELECT engagement_prompt_id FROM scheduled_notifications WHERE id = ?)
                       OR batch_id = (SELECT engagement_prompt_id FROM scheduled_notifications WHERE id = ?)
                    """, notification.id(), notification.id());
        }
    }

    private boolean isEngagement(PendingNotification notification) {
        return "CHECKIN_BATCH".equals(notification.payloadType()) || "WORK_CHECKIN".equals(notification.payloadType()) || "TASK_CHECKIN".equals(notification.payloadType()) || "RETURN_NUDGE".equals(notification.payloadType());
    }

    @Transactional(readOnly = true)
    public EngagementGate checkEngagement(PendingNotification notification, UUID promptId) {
        var rows = jdbcTemplate.query("""
                SELECT u.timezone, t.title, CURRENT_TIMESTAMP AS now,
                  (n.user_id = p.user_id AND n.payload_type = p.kind AND n.channel = 'TELEGRAM'
                    AND p.sent_at IS NULL AND p.answered_at IS NULL AND p.cancelled_at IS NULL
                    AND p.expires_at > NOW() AND s.notifications_enabled AND s.notify_telegram
                    AND EXISTS (SELECT 1 FROM user_identities i WHERE i.user_id = u.id
                      AND i.provider = 'TELEGRAM' AND i.external_id = n.destination)
                    AND CASE WHEN p.kind = 'TASK_CHECKIN' THEN
                      s.task_checkins_enabled AND t.user_id = p.user_id AND NOT t.is_deleted
                      AND (t.status='TODO' OR (t.status='IN_PROGRESS' AND t.started_at IS NULL)) AND t.deadline = p.deadline_snapshot
                      AND t.deadline <= NOW() - INTERVAL '30 minutes'
                      AND (t.planned_date IS NULL OR t.planned_date <= NOW())
                      AND NOT EXISTS (SELECT 1 FROM reminders r WHERE r.task_id = t.id AND r.status = 'PENDING'
                        AND r.fire_at BETWEEN NOW() - INTERVAL '30 minutes' AND NOW() + INTERVAL '30 minutes')
                    WHEN p.kind = 'WORK_CHECKIN' THEN
                      s.task_checkins_enabled AND t.user_id=p.user_id AND NOT t.is_deleted
                      AND t.status='IN_PROGRESS' AND t.started_at=p.started_snapshot
                      AND t.deadline IS NOT DISTINCT FROM p.deadline_snapshot
                      AND t.estimate_minutes IS NOT DISTINCT FROM p.estimate_snapshot
                      AND p.due_at<=NOW() AND (t.planned_date IS NULL OR t.planned_date<=NOW()
                        OR t.planned_date_set_at IS NULL OR t.planned_date_set_at<=t.started_at)
                      AND NOT EXISTS (SELECT 1 FROM reminders r WHERE r.task_id=t.id AND r.status='PENDING'
                        AND r.fire_at BETWEEN NOW()-INTERVAL '30 minutes' AND NOW()+INTERVAL '30 minutes')
                    WHEN p.kind = 'CHECKIN_BATCH' THEN
                      s.task_checkins_enabled AND EXISTS (
                        SELECT 1 FROM engagement_prompts child JOIN tasks item ON item.id=child.task_id AND item.user_id=child.user_id
                        WHERE child.batch_id=p.id AND child.user_id=p.user_id AND child.cancelled_at IS NULL AND child.answer IS NULL
                          AND NOT item.is_deleted AND item.status IN ('TODO','IN_PROGRESS')
                          AND item.deadline IS NOT DISTINCT FROM child.deadline_snapshot AND child.due_at<=NOW()
                          AND (item.planned_date IS NULL OR item.planned_date<=NOW() OR (child.kind='WORK_CHECKIN'
                            AND (item.planned_date_set_at IS NULL OR item.planned_date_set_at<=item.started_at)))
                          AND ((child.kind='TASK_CHECKIN' AND (item.status='TODO' OR item.started_at IS NULL))
                            OR (child.kind='WORK_CHECKIN' AND item.status='IN_PROGRESS' AND item.started_at=child.started_snapshot
                              AND item.estimate_minutes IS NOT DISTINCT FROM child.estimate_snapshot))
                          AND NOT EXISTS(SELECT 1 FROM reminders r WHERE r.task_id=item.id AND r.status='PENDING'
                            AND r.fire_at BETWEEN NOW()-INTERVAL '30 minutes' AND NOW()+INTERVAL '30 minutes'))
                    WHEN p.kind = 'RETURN_NUDGE' THEN
                      s.return_nudges_enabled AND GREATEST(u.last_active_at, s.return_nudges_enabled_at)
                        <= p.created_at - INTERVAL '3 days'
                    ELSE false END) AS valid
                FROM scheduled_notifications n JOIN engagement_prompts p ON p.id = n.engagement_prompt_id
                JOIN users u ON u.id = p.user_id JOIN user_settings s ON s.user_id = u.id
                LEFT JOIN tasks t ON t.id = p.task_id
                WHERE n.id = ? AND n.lease_token = ? AND n.lease_until > NOW() AND NOT n.sent
                  AND n.failed_at IS NULL AND p.id = ?
                """, (rs, row) -> {
            if (!rs.getBoolean("valid")) return new EngagementGate(false, null, null, null);
            var local = rs.getObject("now", OffsetDateTime.class).atZoneSameInstant(ZoneId.of(rs.getString("timezone")));
            OffsetDateTime deferUntil = null;
            if (local.toLocalTime().isBefore(LocalTime.of(9, 0))) {
                deferUntil = local.toLocalDate().atTime(9, 0).atZone(local.getZone()).toOffsetDateTime();
            } else if (!local.toLocalTime().isBefore(LocalTime.of(21, 0))) {
                deferUntil = local.toLocalDate().plusDays(1).atTime(9, 0).atZone(local.getZone()).toOffsetDateTime();
            }
            return new EngagementGate(true, deferUntil, rs.getString("title"), rs.getString("timezone"));
        }, notification.id(), notification.leaseToken(), promptId);
        return rows.isEmpty() ? new EngagementGate(false, null, null, null) : rows.getFirst();
    }

    @Transactional
    public void cancelEngagement(PendingNotification notification) {
        jdbcTemplate.update("""
                UPDATE engagement_prompts SET cancelled_at = NOW()
                WHERE id IN (SELECT engagement_prompt_id FROM scheduled_notifications
                  WHERE id = ? AND lease_token = ? AND NOT sent)
                   OR batch_id IN (SELECT engagement_prompt_id FROM scheduled_notifications
                  WHERE id = ? AND lease_token = ? AND NOT sent)
                """, notification.id(), notification.leaseToken(), notification.id(), notification.leaseToken());
        jdbcTemplate.update("DELETE FROM scheduled_notifications WHERE id = ? AND lease_token = ? AND NOT sent",
                notification.id(), notification.leaseToken());
    }

    @Transactional
    public void deferEngagement(PendingNotification notification, OffsetDateTime until) {
        jdbcTemplate.update("""
                UPDATE scheduled_notifications SET next_attempt_at = ?, lease_token = NULL, lease_until = NULL,
                  retry_count = GREATEST(0, retry_count - 1)
                WHERE id = ? AND lease_token = ? AND NOT sent
                """, until, notification.id(), notification.leaseToken());
    }

    public record EngagementGate(boolean valid, OffsetDateTime deferUntil, String title, String timezone) {}

    @Transactional
    public void recordFailure(PendingNotification notification, boolean permanent, Duration delay, String errorCode) {
        boolean failed = permanent || notification.attempt() >= maxAttempts;
        if ("WIDGET_SYNC".equals(notification.payloadType())) {
            jdbcTemplate.update("""
                    UPDATE scheduled_notifications SET
                      next_attempt_at=CASE WHEN widget_revision=(?::jsonb->>'revision')::bigint THEN NOW()+(?*INTERVAL '1 millisecond') ELSE NOW() END,
                      failed_at=CASE WHEN widget_revision=(?::jsonb->>'revision')::bigint AND ? THEN NOW() ELSE NULL END,
                      retry_count=CASE WHEN widget_revision=(?::jsonb->>'revision')::bigint THEN retry_count ELSE 0 END,
                      last_error=CASE WHEN widget_revision=(?::jsonb->>'revision')::bigint THEN ? ELSE NULL END,
                      lease_token=NULL,lease_until=NULL
                    WHERE id=? AND lease_token=? AND NOT sent AND failed_at IS NULL AND channel=? AND destination=?
                    """,notification.payload(),delay.toMillis(),notification.payload(),failed,notification.payload(),
                    notification.payload(),errorCode,notification.id(),notification.leaseToken(),notification.channel(),notification.destination());
            return;
        }
        jdbcTemplate.update("""
                UPDATE scheduled_notifications SET next_attempt_at = NOW() + (? * INTERVAL '1 millisecond'),
                    failed_at = CASE WHEN ? THEN NOW() ELSE NULL END,
                    last_error = ?, lease_token = NULL, lease_until = NULL
                WHERE id = ? AND lease_token = ? AND sent = false AND failed_at IS NULL AND channel=? AND destination=?
                """, delay.toMillis(), failed, errorCode,
                notification.id(), notification.leaseToken(),notification.channel(),notification.destination());
    }
}
