package ru.taskflow.notificationworker.infrastructure.db;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ScheduledNotificationPollerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private ScheduledNotificationPoller poller;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        poller = new ScheduledNotificationPoller(jdbcTemplate);
        ReflectionTestUtils.setField(poller, "maxAttempts", 3);
        ReflectionTestUtils.setField(poller, "leaseSeconds", 120);
        jdbcTemplate.execute("DROP TABLE IF EXISTS scheduled_notifications");
        jdbcTemplate.execute("""
                CREATE TABLE scheduled_notifications (
                    id UUID PRIMARY KEY, channel VARCHAR(16) NOT NULL, destination VARCHAR(320) NOT NULL,
                    fire_at TIMESTAMP WITH TIME ZONE NOT NULL, payload_type VARCHAR(32) NOT NULL, payload JSONB,
                    sent BOOLEAN DEFAULT FALSE, sent_at TIMESTAMP WITH TIME ZONE, retry_count INT DEFAULT 0,
                    lease_token UUID, lease_until TIMESTAMP WITH TIME ZONE, quiet_deferred_until TIMESTAMPTZ,
                    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                    failed_at TIMESTAMP WITH TIME ZONE, last_error VARCHAR(255)
                )
                """);
    }

    @Test
    void successfulClaimIsNotDeliveredAgain() {
        UUID id = insertPending();
        var claim = poller.claimPending(1).getFirst();
        assertThat(claim.id()).isEqualTo(id);
        assertThat(claim.attempt()).isEqualTo(1);
        assertThat(poller.isClaimActive(claim)).isTrue();
        assertThat(poller.claimPending(1)).isEmpty();

        poller.markAsSent(claim);

        assertThat(jdbcTemplate.queryForObject("SELECT sent FROM scheduled_notifications WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(poller.claimPending(1)).isEmpty();
    }

    @Test
    void workersClaimDisjointRows() throws Exception {
        for (int index = 0; index < 12; index++) {
            insertPending();
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> poller.claimPending(6));
            var second = executor.submit(() -> poller.claimPending(6));
            var firstRows = first.get();
            var secondRows = second.get();
            assertThat(firstRows).hasSize(6);
            assertThat(secondRows).hasSize(6);
            var identifiers = new HashSet<UUID>();
            firstRows.forEach(row -> identifiers.add(row.id()));
            secondRows.forEach(row -> assertThat(identifiers.add(row.id())).isTrue());
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldOwnerCannotAcknowledgeIt() {
        UUID id = insertPending();
        var oldClaim = poller.claimPending(1).getFirst();
        expireLease(id);
        assertThat(poller.isClaimActive(oldClaim)).isFalse();
        var newClaim = poller.claimPending(1).getFirst();
        assertThat(newClaim.attempt()).isEqualTo(2);
        assertThat(newClaim.leaseToken()).isNotEqualTo(oldClaim.leaseToken());

        poller.markAsSent(oldClaim);
        poller.recordFailure(oldClaim, true, Duration.ZERO, "STALE_OWNER");

        assertThat(poller.isClaimActive(newClaim)).isTrue();
        poller.markAsSent(newClaim);
        assertThat(poller.isClaimActive(newClaim)).isFalse();
    }

    @Test
    void retriesWaitForNextAttemptAndExhaustedJobsStayVisible() {
        UUID id = insertPending();
        var claim = poller.claimPending(1).getFirst();
        poller.recordFailure(claim, false, Duration.ofMinutes(1), "TIMEOUT");
        assertThat(poller.claimPending(1)).isEmpty();
        makeDue(id);
        var secondClaim = poller.claimPending(1).getFirst();
        poller.recordFailure(secondClaim, false, Duration.ZERO, "TIMEOUT");
        var finalClaim = poller.claimPending(1).getFirst();
        poller.recordFailure(finalClaim, false, Duration.ZERO, "TIMEOUT");

        assertThat(poller.claimPending(1)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT failed_at FROM scheduled_notifications WHERE id = ?",
                OffsetDateTime.class, id)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT sent FROM scheduled_notifications WHERE id = ?", Boolean.class, id))
                .isFalse();
    }

    @Test
    void repeatedWorkerCrashesEventuallyFailTheJob() {
        UUID id = insertPending();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(poller.claimPending(1)).hasSize(1);
            expireLease(id);
        }
        assertThat(poller.claimPending(1)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT last_error FROM scheduled_notifications WHERE id = ?",
                String.class, id)).isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    @Test
    void cancellationRemovesAnAlreadyClaimedJob() {
        UUID id = insertPending();
        var claim = poller.claimPending(1).getFirst();
        jdbcTemplate.update("DELETE FROM scheduled_notifications WHERE id = ? AND sent = false", id);
        assertThat(poller.isClaimActive(claim)).isFalse();
        poller.markAsSent(claim);
        poller.recordFailure(claim, false, Duration.ZERO, "TIMEOUT");
        assertThat(poller.claimPending(1)).isEmpty();
    }

    @Test
    void permanentFailureDoesNotRetry() {
        UUID id = insertPending();
        var claim = poller.claimPending(1).getFirst();
        poller.recordFailure(claim, true, Duration.ZERO, "TELEGRAM_HTTP_403");
        assertThat(poller.claimPending(1)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT failed_at FROM scheduled_notifications WHERE id = ?",
                OffsetDateTime.class, id)).isNotNull();
    }

    @Test
    void queueMetricsKeepFailedAndLeasedJobsSeparateFromReady() {
        insertPending();
        insertPending();
        insertPending();
        var claims = poller.claimPending(2);
        poller.recordFailure(claims.getFirst(), true, Duration.ZERO, "INVALID_PAYLOAD");
        var registry = new SimpleMeterRegistry();
        var metrics = new NotificationQueueMetrics(jdbcTemplate, registry);

        metrics.refresh();

        assertThat(registry.get("taskflow.notifications.ready").gauge().value()).isEqualTo(1);
        assertThat(registry.get("taskflow.notifications.failed").gauge().value()).isEqualTo(1);
        assertThat(registry.get("taskflow.notifications.leased").gauge().value()).isEqualTo(1);
        assertThat(registry.get("taskflow.notifications.oldest.due.seconds").gauge().value()).isGreaterThan(0);
    }

    private void expireLease(UUID id) {
        jdbcTemplate.update("UPDATE scheduled_notifications SET lease_until = NOW() - INTERVAL '1 second' WHERE id = ?", id);
    }

    private void makeDue(UUID id) {
        jdbcTemplate.update("UPDATE scheduled_notifications SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = ?", id);
    }

    @Test
    void newerWidgetStateIsNotAcknowledgedOrFailedByOlderDelivery() {
        jdbcTemplate.execute("ALTER TABLE scheduled_notifications ADD COLUMN widget_revision BIGINT");
        UUID id=insertPending();
        jdbcTemplate.update("UPDATE scheduled_notifications SET payload_type='WIDGET_SYNC',payload='{\"revision\":1}',widget_revision=1 WHERE id=?",id);
        var first=poller.claimPending(1).getFirst();
        jdbcTemplate.update("UPDATE scheduled_notifications SET payload='{\"revision\":2}',widget_revision=2 WHERE id=?",id);
        poller.markAsSent(first);
        assertThat(jdbcTemplate.queryForObject("SELECT sent FROM scheduled_notifications WHERE id=?",Boolean.class,id)).isFalse();
        var second=poller.claimPending(1).getFirst();
        jdbcTemplate.update("UPDATE scheduled_notifications SET payload='{\"revision\":3}',widget_revision=3 WHERE id=?",id);
        poller.recordFailure(second,true,Duration.ofHours(1),"TELEGRAM_HTTP_400");
        assertThat(jdbcTemplate.queryForObject("SELECT failed_at IS NULL FROM scheduled_notifications WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT retry_count FROM scheduled_notifications WHERE id=?",Integer.class,id)).isZero();
        var latest=poller.claimPending(1).getFirst();poller.markAsSent(latest);
        assertThat(jdbcTemplate.queryForObject("SELECT sent FROM scheduled_notifications WHERE id=?",Boolean.class,id)).isTrue();
    }
    @Test
    void transferredDestinationCannotBeAcknowledgedByOldClaim() {
        UUID id=insertPending();var old=poller.claimPending(1).getFirst();
        jdbcTemplate.update("UPDATE scheduled_notifications SET destination='9999' WHERE id=?",id);
        assertThat(poller.isClaimActive(old)).isFalse();poller.markAsSent(old);
        poller.recordFailure(old,true,Duration.ZERO,"OLD_DESTINATION");
        assertThat(jdbcTemplate.queryForObject("SELECT sent OR failed_at IS NOT NULL FROM scheduled_notifications WHERE id=?",Boolean.class,id)).isFalse();
    }

    private UUID insertPending() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO scheduled_notifications (id, channel, destination, fire_at, payload_type, payload, next_attempt_at)
                VALUES (?, 'TELEGRAM', '12345', NOW() - INTERVAL '1 minute', 'TASK_REMINDER', '{}'::jsonb, NOW() - INTERVAL '1 minute')
                """, id);
        return id;
    }
}
