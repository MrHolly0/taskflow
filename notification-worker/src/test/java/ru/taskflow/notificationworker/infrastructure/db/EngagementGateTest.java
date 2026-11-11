package ru.taskflow.notificationworker.infrastructure.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class EngagementGateTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate jdbc;
    ScheduledNotificationPoller poller;
    UUID user, task, prompt, notification;
    OffsetDateTime now;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        jdbc.execute("""
                CREATE TABLE users(id UUID PRIMARY KEY, timezone TEXT, last_active_at TIMESTAMPTZ);
                CREATE TABLE user_settings(user_id UUID PRIMARY KEY, notifications_enabled BOOLEAN DEFAULT true,
                  notify_telegram BOOLEAN DEFAULT true, task_checkins_enabled BOOLEAN DEFAULT true,
                  return_nudges_enabled BOOLEAN DEFAULT true, task_checkins_enabled_at TIMESTAMPTZ, return_nudges_enabled_at TIMESTAMPTZ);
                CREATE TABLE user_identities(user_id UUID, provider TEXT, external_id TEXT);
                CREATE TABLE tasks(id UUID PRIMARY KEY, user_id UUID, title TEXT, deadline TIMESTAMPTZ,
                  planned_date TIMESTAMPTZ, planned_date_set_at TIMESTAMPTZ,started_at TIMESTAMPTZ,estimate_minutes INT,status TEXT DEFAULT 'TODO', is_deleted BOOLEAN DEFAULT false);
                CREATE TABLE reminders(task_id UUID, status TEXT DEFAULT 'PENDING', fire_at TIMESTAMPTZ);
                CREATE TABLE engagement_prompts(id UUID PRIMARY KEY, user_id UUID, task_id UUID, kind TEXT,
                  deadline_snapshot TIMESTAMPTZ,started_snapshot TIMESTAMPTZ,estimate_snapshot INT,due_at TIMESTAMPTZ, created_at TIMESTAMPTZ, expires_at TIMESTAMPTZ,
                  sent_at TIMESTAMPTZ, answered_at TIMESTAMPTZ, cancelled_at TIMESTAMPTZ);
                CREATE TABLE scheduled_notifications(id UUID PRIMARY KEY, user_id UUID, engagement_prompt_id UUID,
                  channel TEXT, destination TEXT, payload_type TEXT, payload JSONB, fire_at TIMESTAMPTZ,
                  sent BOOLEAN DEFAULT false, sent_at TIMESTAMPTZ, retry_count INT DEFAULT 0,
                  failed_at TIMESTAMPTZ, lease_token UUID, lease_until TIMESTAMPTZ, last_error TEXT,
                  next_attempt_at TIMESTAMPTZ DEFAULT NOW());
                """);
        poller = new ScheduledNotificationPoller(jdbc);
        ReflectionTestUtils.setField(poller, "maxAttempts", 5);
        ReflectionTestUtils.setField(poller, "leaseSeconds", 120);
        now = OffsetDateTime.now(ZoneOffset.UTC);
        user = UUID.randomUUID(); task = UUID.randomUUID(); prompt = UUID.randomUUID(); notification = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?, ?, ?)", user, zoneAtHour(12), now.minusDays(4));
        jdbc.update("INSERT INTO user_settings(user_id, task_checkins_enabled_at, return_nudges_enabled_at) VALUES (?, ?, ?)",
                user, now.minusDays(5), now.minusDays(5));
        jdbc.update("INSERT INTO user_identities VALUES (?, 'TELEGRAM', '12345')", user);
        jdbc.update("INSERT INTO tasks(id, user_id, title, deadline) VALUES (?, ?, 'Текущее название', ?)", task, user, now.minusHours(1));
        jdbc.update("INSERT INTO reminders(task_id, fire_at) VALUES (?, ?)", task, now.minusHours(2));
        jdbc.update("INSERT INTO engagement_prompts(id, user_id, task_id, kind, deadline_snapshot, created_at, expires_at) VALUES (?, ?, ?, 'TASK_CHECKIN', ?, ?, ?)",
                prompt, user, task, now.minusHours(1), now.minusMinutes(1), now.plusDays(7));
        jdbc.update("INSERT INTO scheduled_notifications(id, user_id, engagement_prompt_id, channel, destination, payload_type, payload, fire_at) VALUES (?, ?, ?, 'TELEGRAM', '12345', 'TASK_CHECKIN', '{}'::jsonb, ?)",
                notification, user, prompt, now.minusMinutes(1));
    }

    @Test
    void workWithoutDeadlineIsCheckedAndLateReschedulingInvalidatesIt() {
        var started=now.minusHours(2);
        jdbc.update("UPDATE tasks SET status='IN_PROGRESS',deadline=NULL,started_at=?,estimate_minutes=30,planned_date=?,planned_date_set_at=?",
                started,now.plusDays(2),started.minusHours(1));
        jdbc.update("UPDATE engagement_prompts SET kind='WORK_CHECKIN',deadline_snapshot=NULL,started_snapshot=?,estimate_snapshot=30,due_at=?",started,now.minusHours(1));
        jdbc.update("UPDATE scheduled_notifications SET payload_type='WORK_CHECKIN'");
        var claim=poller.claimPending(1).getFirst();assertThat(poller.checkEngagement(claim,prompt).valid()).isTrue();
        jdbc.update("UPDATE tasks SET planned_date_set_at=?",started.plusHours(1));
        assertThat(poller.checkEngagement(claim,prompt).valid()).isFalse();
    }
    @Test
    void changedEstimateOrWorkSessionInvalidatesQueuedQuestion() {
        var started=now.minusHours(2);
        jdbc.update("UPDATE tasks SET status='IN_PROGRESS',started_at=?,estimate_minutes=30",started);
        jdbc.update("UPDATE engagement_prompts SET kind='WORK_CHECKIN',started_snapshot=?,estimate_snapshot=30,due_at=?",started,now.minusHours(1));
        jdbc.update("UPDATE scheduled_notifications SET payload_type='WORK_CHECKIN'");
        var claim=poller.claimPending(1).getFirst();assertThat(poller.checkEngagement(claim,prompt).valid()).isTrue();
        jdbc.update("UPDATE tasks SET estimate_minutes=60");assertThat(poller.checkEngagement(claim,prompt).valid()).isFalse();
        jdbc.update("UPDATE tasks SET estimate_minutes=30,started_at=?",started.plusMinutes(1));
        assertThat(poller.checkEngagement(claim,prompt).valid()).isFalse();
    }

    @Test
    void checksCurrentStateAndRecordsSuccessfulDelivery() {
        var claim = claim();
        var gate = poller.checkEngagement(claim, prompt);
        assertThat(gate.valid()).isTrue();
        assertThat(gate.deferUntil()).isNull();
        assertThat(gate.title()).isEqualTo("Текущее название");
        poller.markAsSent(claim);
        assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM engagement_prompts", Boolean.class)).isTrue();
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
        assertThat(poller.claimPending(1)).isEmpty();
    }

    @Test
    void revalidatesTaskStatusDeadlineAndOwnershipBeforeDelivery() {
        var claim = claim();
        assertThat(poller.checkEngagement(claim, prompt).valid()).isTrue();
        jdbc.update("UPDATE tasks SET status = 'DONE'");
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
        jdbc.update("UPDATE tasks SET status = 'TODO', deadline = deadline + INTERVAL '1 hour'");
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
        jdbc.update("UPDATE tasks SET deadline = deadline - INTERVAL '1 hour', user_id = ?", UUID.randomUUID());
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
        poller.cancelEngagement(claim);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NOT NULL FROM engagement_prompts", Boolean.class)).isTrue();
    }

    @Test
    void optOutIdentityChangesAndReminderCancellationMakeDeliveryObsolete() {
        var claim = claim();
        jdbc.update("UPDATE user_settings SET task_checkins_enabled = false");
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
        jdbc.update("UPDATE user_settings SET task_checkins_enabled = true, notify_telegram = false");
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
        jdbc.update("UPDATE user_settings SET notify_telegram = true");
        jdbc.update("UPDATE user_identities SET external_id = '54321'");
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
        jdbc.update("UPDATE user_identities SET external_id = '12345'");
        jdbc.update("UPDATE reminders SET status = 'CANCELLED'");
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
    }

    @Test
    void returnIsCancelledWhenPersonHasAlreadyReturned() {
        jdbc.update("UPDATE engagement_prompts SET kind = 'RETURN_NUDGE', task_id = NULL, deadline_snapshot = NULL");
        jdbc.update("UPDATE scheduled_notifications SET payload_type = 'RETURN_NUDGE'");
        var claim = claim();
        assertThat(poller.checkEngagement(claim, prompt).valid()).isTrue();
        jdbc.update("UPDATE users SET last_active_at = NOW()");
        assertThat(poller.checkEngagement(claim, prompt).valid()).isFalse();
    }

    @Test
    void quietHoursDeferUntilLocalNineWithoutConsumingAttempt() {
        jdbc.update("UPDATE users SET timezone = ?", zoneAtHour(21));
        var claim = claim();
        var gate = poller.checkEngagement(claim, prompt);
        assertThat(gate.valid()).isTrue();
        assertThat(gate.deferUntil()).isAfter(now);
        assertThat(gate.deferUntil().getHour()).isEqualTo(9);
        poller.deferEngagement(claim, gate.deferUntil());
        assertThat(jdbc.queryForObject("SELECT retry_count FROM scheduled_notifications", Integer.class)).isZero();
        assertThat(poller.claimPending(1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM engagement_prompts", OffsetDateTime.class)).isNull();
    }

    @Test
    void staleLeaseCannotCancelOrAcknowledgeAnotherWorkersPrompt() {
        var first = claim();
        jdbc.update("UPDATE scheduled_notifications SET lease_until = NOW() - INTERVAL '1 second'");
        var second = claim();
        assertThat(poller.checkEngagement(first, prompt).valid()).isFalse();
        poller.cancelEngagement(first);
        poller.markAsSent(first);
        assertThat(jdbc.queryForObject("SELECT sent_at FROM engagement_prompts", OffsetDateTime.class)).isNull();
        assertThat(poller.isClaimActive(second)).isTrue();
        poller.markAsSent(second);
        assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM engagement_prompts", Boolean.class)).isTrue();
    }

    @Test
    void currentTimezoneTravelsWithTheDeliveryDecision() {
        var claim = claim();
        String updatedZone = zoneAtHour(13);
        jdbc.update("UPDATE users SET timezone = ?", updatedZone);
        var gate = poller.checkEngagement(claim, prompt);
        assertThat(gate.valid()).isTrue();
        assertThat(gate.timezone()).isEqualTo(updatedZone);
    }

    @Test
    void accountCancellationAndDeliveryAcknowledgementUseCompatibleLockOrder() throws Exception {
        var claim = claim();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.query("SELECT id FROM engagement_prompts WHERE id = ? FOR UPDATE", (rs, row) -> rs.getObject(1), prompt);
                locked.countDown();
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(4).toNanos();
                boolean waiting = false;
                while (!waiting && System.nanoTime() < deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'engagement_ack_test' AND wait_event_type = 'Lock')", Boolean.class));
                    if (!waiting) {
                        try { Thread.sleep(20); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    }
                }
                assertThat(waiting).isTrue();
                jdbc.update("UPDATE engagement_prompts SET cancelled_at = NOW() WHERE id = ?", prompt);
                jdbc.update("DELETE FROM scheduled_notifications WHERE id = ?", notification);
            }));
            assertThat(locked.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var acknowledge = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL application_name = 'engagement_ack_test'");
                poller.markAsSent(claim);
            }));
            cancel.get(8, java.util.concurrent.TimeUnit.SECONDS);
            acknowledge.get(8, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sent_at FROM engagement_prompts", OffsetDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NOT NULL FROM engagement_prompts", Boolean.class)).isTrue();
    }

    private PendingNotification claim() { return poller.claimPending(1).getFirst(); }
    private String zoneAtHour(int target) {
        int offset = target - now.getHour();
        if (offset > 14) offset -= 24;
        if (offset < -12) offset += 24;
        return ZoneOffset.ofHours(offset).getId();
    }
}
