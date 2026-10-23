package ru.taskflow.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.telegram.application.TelegramInboxMetrics;
import ru.taskflow.telegram.application.TelegramUpdateInbox;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUpdate;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TelegramUpdateInboxIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("app", "munin-reliability-test");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private TelegramUpdateInbox inbox;

    @BeforeAll
    static void schema() throws Exception {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("gradlew"))) root = root.getParent();
        String yaml = Files.readString(root.resolve(
                "core-service/app/src/main/resources/db/changelog/041-telegram-update-inbox.yaml"));
        String sql = yaml.substring(yaml.indexOf("sql: |") + 6).stripIndent();
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement.trim());
        }
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE telegram_update_inbox");
        inbox = new TelegramUpdateInbox(jdbc, new ObjectMapper().findAndRegisterModules(), 3);
    }

    @Test
    void duplicateDeliveryIsStoredOnceAndCompletedPayloadIsDiscarded() {
        enqueue(1, 10);
        enqueue(1, 10);
        var update = inbox.claim().orElseThrow();
        inbox.complete(update);
        enqueue(1, 10);
        assertThat(inbox.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telegram_update_inbox", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM telegram_update_inbox", String.class)).isEqualTo("{}");
    }

    @Test
    void anotherSenderCanProceedButSameSenderWaitsForOlderUpdate() {
        enqueue(1, 10);
        enqueue(2, 10);
        enqueue(3, 20);
        var first = inbox.claim().orElseThrow();
        assertThat(first.id()).isEqualTo(1);
        var other = inbox.claim().orElseThrow();
        assertThat(other.id()).isEqualTo(3);
        assertThat(inbox.claim()).isEmpty();
        inbox.complete(first);
        assertThat(inbox.claim().orElseThrow().id()).isEqualTo(2);
    }

    @Test
    void concurrentWorkersDoNotClaimTheSameUpdate() throws Exception {
        enqueue(1, 10);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> claim = () -> inbox.claim().isPresent();
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            assertThat(java.util.List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void expiredWorkerCannotCompleteNewLeaseAndCheckpointSurvivesRetry() {
        enqueue(1, 10);
        var stale = inbox.claim().orElseThrow();
        inbox.checkpoint(1, "proposal", "saved-result");
        jdbc.update("UPDATE telegram_update_inbox SET lease_until = NOW() - INTERVAL '1 second'");
        var resumed = inbox.claim().orElseThrow();
        assertThat(resumed.token()).isNotEqualTo(stale.token());
        assertThat(resumed.attempt()).isEqualTo(2);
        inbox.complete(stale);
        assertThat(jdbc.queryForObject("SELECT status FROM telegram_update_inbox", String.class)).isEqualTo("PROCESSING");
        assertThat(inbox.checkpoint(1, "proposal", String.class)).contains("saved-result");
        assertThat(inbox.renew(stale)).isFalse();
        assertThat(inbox.renew(resumed)).isTrue();
    }

    @Test
    void failedAttemptIsDeferredAndFinalFailureRemainsVisible() {
        enqueue(1, 10);
        var update = inbox.claim().orElseThrow();
        inbox.fail(update, true, Duration.ofMinutes(1), "ResourceAccessException");
        assertThat(inbox.claim()).isEmpty();
        jdbc.update("UPDATE telegram_update_inbox SET next_attempt_at = NOW() - INTERVAL '1 second'");
        var retry = inbox.claim().orElseThrow();
        inbox.fail(retry, false, Duration.ZERO, "IllegalArgumentException");
        assertThat(jdbc.queryForObject("SELECT status FROM telegram_update_inbox", String.class)).isEqualTo("FAILED");
        assertThat(inbox.claim()).isEmpty();
        new TelegramInboxMetrics(jdbc).refresh();
    }

    @Test
    void fullQueueRejectsNewMessagesButAcknowledgesDuplicates() {
        enqueue(1, 10);
        enqueue(2, 20);
        enqueue(3, 30);
        enqueue(1, 10);
        assertThatThrownBy(() -> enqueue(4, 40)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telegram_update_inbox", Integer.class)).isEqualTo(3);
    }

    @Test
    void repeatedCrashesEventuallyBecomeVisibleFailure() {
        enqueue(1, 10);
        for (int attempt = 1; attempt <= 6; attempt++) {
            assertThat(inbox.claim().orElseThrow().attempt()).isEqualTo(attempt);
            jdbc.update("UPDATE telegram_update_inbox SET lease_until = NOW() - INTERVAL '1 second'");
        }
        assertThat(inbox.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM telegram_update_inbox", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM telegram_update_inbox", String.class))
                .isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    @Test
    void concurrentAdmissionsCannotExceedGlobalCapacity() throws Exception {
        enqueue(1, 10);
        enqueue(2, 20);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(java.util.List.<Callable<Boolean>>of(() -> tryEnqueue(3), () -> tryEnqueue(4)));
            assertThat(java.util.List.of(results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telegram_update_inbox", Integer.class)).isEqualTo(3);
    }

    private boolean tryEnqueue(long id) {
        try {
            enqueue(id, id + 100);
            return true;
        } catch (ResponseStatusException full) {
            return false;
        }
    }

    @Test
    void cleanupRemovesOnlyExpiredTerminalUpdates() {
        enqueue(1, 10);
        enqueue(2, 20);
        enqueue(3, 30);
        jdbc.update("UPDATE telegram_update_inbox SET status = 'DONE', processed_at = NOW() - INTERVAL '8 days' WHERE update_id = 1");
        jdbc.update("UPDATE telegram_update_inbox SET status = 'FAILED', received_at = NOW() - INTERVAL '31 days' WHERE update_id = 2");
        inbox.prune();
        assertThat(jdbc.queryForList("SELECT update_id FROM telegram_update_inbox", Long.class)).containsExactly(3L);
    }

    private void enqueue(long id, long sender) {
        tx.executeWithoutResult(ignored -> inbox.enqueue(new TelegramUpdate(id,
                new TelegramMessage(id, new TelegramUser(sender, "user", "Name", null),
                        new TelegramChat(sender), "дело", null), null)));
    }
}
