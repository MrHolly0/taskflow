package ru.taskflow.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.FocusHintGenerator;
import ru.taskflow.assistant.api.FocusHintGeneration;
import ru.taskflow.assistant.api.FocusPlannedDateGenerator;
import ru.taskflow.assistant.api.PlannedDateGeneration;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.application.AssistantRateLimiter;
import ru.taskflow.assistant.application.ProposalActionTransaction;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.RecurrenceType;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.RecurrenceRule;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.task.application.TaskCreationService;
import ru.taskflow.task.application.TaskReminderService;
import ru.taskflow.task.infrastructure.persistence.TaskRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.taskflow.user.api.UserService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TaskIdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
    }

    @Autowired private TaskService tasks;
    @Autowired private TaskCreationService creation;
    @Autowired private AssistantService assistant;
    @Autowired private ProposalActionTransaction actionTransaction;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskReminderService reminders;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private NlpGatewayService nlp;
    @MockBean private AssistantRateLimiter limiter;
    @MockBean private FocusHintGenerator hintGenerator;
    @MockBean private FocusPlannedDateGenerator dateGenerator;

    @BeforeEach
    void allowRequests() {
        when(limiter.allow(any())).thenReturn(true);
    }

    @Test
    void concurrentCreationReturnsOneSavedResult() throws Exception {
        UUID userId = newUser();
        var request = request("Одна задача");
        var responses = concurrently(() -> creation.create(userId, request, "create-1", true));

        assertThat(responses.get(0).id()).isEqualTo(responses.get(1).id());
        assertThat(taskCount(userId)).isEqualTo(1);
        assertThat(creation.create(userId, request, "create-1", true)).isEqualTo(responses.getFirst());
    }

    @Test
    void keyIsScopedToUserAndRejectsAnotherPayload() {
        UUID userId = newUser();
        creation.create(userId, request("Первая"), "create-1", true);

        assertThatThrownBy(() -> creation.create(userId, request("Другая"), "create-1", true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
        UUID anotherUser = newUser();
        creation.create(anotherUser, request("Другая"), "create-1", true);
        assertThat(taskCount(userId)).isEqualTo(1);
        assertThat(taskCount(anotherUser)).isEqualTo(1);
    }

    @Test
    void failedCreationRollsBackRequestReservation() {
        UUID userId = newUser();
        var invalid = new CreateTaskRequest("Не создана", null, null, null, UUID.randomUUID(),
                null, null, null, null);
        assertThatThrownBy(() -> creation.create(userId, invalid, "retry-1", true)).isInstanceOf(RuntimeException.class);

        creation.create(userId, request("Исправлена"), "retry-1", true);

        assertThat(taskCount(userId)).isEqualTo(1);
    }

    @Test
    void concurrentCompletionAdvancesRecurrenceOnlyOnceEvenAfterReopening() throws Exception {
        UUID userId = newUser();
        var rule = new RecurrenceRule(RecurrenceType.DAILY, 1, null, null, null);
        var task = tasks.create(userId, new CreateTaskRequest("Повтор", null, null, null,
                null, null, null, null, null, rule));
        concurrently(() -> {
            tasks.complete(userId, task.id());
            return true;
        });
        assertThat(taskCount(userId)).isEqualTo(2);
        var completedAt = tasks.findById(userId, task.id()).completedAt();
        tasks.complete(userId, task.id());
        assertThat(tasks.findById(userId, task.id()).completedAt()).isEqualTo(completedAt);

        tasks.update(userId, task.id(), new UpdateTaskRequest(null, null, null, TaskStatus.TODO,
                null, null, null, null, null));
        tasks.complete(userId, task.id());
        assertThat(taskCount(userId)).isEqualTo(2);
    }

    @Test
    void concurrentProposalConfirmationAndReplayDoNotRepeatActions() throws Exception {
        UUID userId = newUser();
        UUID proposalId = proposal(userId, "{\"title\":\"Из предложения\"}");

        var results = concurrently(() -> assistant.apply(userId, proposalId));

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(ProposalStatus.APPLIED);
            assertThat(result.appliedCount()).isEqualTo(1);
        });
        assertThat(results.get(0).outcomes()).isEqualTo(results.get(1).outcomes());
        assertThat(assistant.apply(userId, proposalId)).isEqualTo(results.getFirst());
        assertThat(taskCount(userId)).isEqualTo(1);
    }

    @Test
    void failedActionRollsBackItsTaskButKeepsOtherActions() {
        UUID userId = newUser();
        UUID proposalId = proposal(userId,
                "{\"title\":\"Первая\"}",
                "{\"description\":\"Без обязательного названия\"}",
                "{\"title\":\"Третья\"}");

        var result = assistant.apply(userId, proposalId);

        assertThat(result.status()).isEqualTo(ProposalStatus.PARTIALLY_APPLIED);
        assertThat(result.appliedCount()).isEqualTo(2);
        assertThat(taskCount(userId)).isEqualTo(2);
        assertThat(tasks.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20)))
                .extracting(task -> task.title()).containsExactlyInAnyOrder("Первая", "Третья");
    }

    @Test
    void pendingProposalResumesFromCommittedActionAfterInterruptedConfirmation() {
        UUID userId = newUser();
        UUID proposalId = proposal(userId, "{\"title\":\"Первая\"}", "{\"title\":\"Вторая\"}");
        UUID actionId = jdbc.queryForObject("SELECT id FROM assistant_proposal_actions WHERE proposal_id = ? AND ordinal = 1",
                UUID.class, proposalId);
        UUID firstTaskId = actionTransaction.execute(actionId, () -> tasks.createQuick(userId, request("Первая")).id());

        var result = assistant.apply(userId, proposalId);

        assertThat(result.appliedCount()).isEqualTo(2);
        assertThat(result.outcomes().getFirst().taskId()).isEqualTo(firstTaskId);
        assertThat(taskCount(userId)).isEqualTo(2);
    }

    @Test
    void keyedFallbackSurvivesRetryWithoutCreatingAnotherTask() {
        UUID userId = newUser();
        when(nlp.callWithTools(any())).thenReturn(LlmToolResponse.unavailable());

        var first = assistant.handleText(userId, "Купить хлеб", AssistantChannel.TELEGRAM,
                AssistantEntryPoint.CHAT, "tg:update:123");
        var repeated = assistant.handleText(userId, "Купить хлеб", AssistantChannel.TELEGRAM,
                AssistantEntryPoint.CHAT, "tg:update:123");

        assertThat(repeated).isEqualTo(first);
        assertThat(taskCount(userId)).isEqualTo(1);
    }

    @Test
    void expiredInterruptedProposalStillReportsCommittedAction() {
        UUID userId = newUser();
        UUID proposalId = proposal(userId, "{\"title\":\"Первая\"}", "{\"title\":\"Вторая\"}");
        UUID actionId = jdbc.queryForObject("SELECT id FROM assistant_proposal_actions WHERE proposal_id = ? AND ordinal = 1",
                UUID.class, proposalId);
        actionTransaction.execute(actionId, () -> tasks.createQuick(userId, request("Первая")).id());
        jdbc.update("UPDATE assistant_proposals SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?", proposalId);

        var result = assistant.apply(userId, proposalId);

        assertThat(result.status()).isEqualTo(ProposalStatus.PARTIALLY_APPLIED);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.outcomes().get(1).success()).isFalse();
        assertThat(taskCount(userId)).isEqualTo(1);
    }

    @Test
    void failedActionRollsBackCreationAndSuccessMarkerTogether() {
        UUID userId = newUser();
        UUID proposalId = proposal(userId, "{\"title\":\"Повтор после сбоя\"}");
        UUID actionId = jdbc.queryForObject("SELECT id FROM assistant_proposal_actions WHERE proposal_id = ?",
                UUID.class, proposalId);

        assertThatThrownBy(() -> actionTransaction.execute(actionId, () -> {
            tasks.createQuick(userId, request("Откатить вместе с действием"));
            throw new IllegalStateException("обрыв до фиксации действия");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(taskCount(userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT applied_task_id FROM assistant_proposal_actions WHERE id = ?",
                UUID.class, actionId)).isNull();
        assertThat(assistant.apply(userId, proposalId).appliedCount()).isEqualTo(1);
        assertThat(taskCount(userId)).isEqualTo(1);
    }

    @Test
    void delayedHintRunsWithoutTransactionAndDoesNotOverwriteCompletion() {
        UUID userId = newUser();
        var task = tasks.createQuick(userId, request("Подготовить подробный план защиты диплома"));
        when(hintGenerator.generate(any(), any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            tasks.complete(userId, task.id());
            return new FocusHintGeneration("Открой план", 5, 5);
        });

        assertThat(tasks.getFocusHint(userId, task.id()).hint()).isNull();

        assertThat(tasks.findById(userId, task.id()).status()).isEqualTo(TaskStatus.DONE);
        assertThat(jdbc.queryForObject("SELECT first_step_hint FROM tasks WHERE id = ?", String.class, task.id())).isNull();
    }

    @Test
    void boardCompletionAdvancesRecurrenceAndRepeatedPatchDoesNotDuplicateIt() {
        UUID userId = newUser();
        var rule = new RecurrenceRule(RecurrenceType.DAILY, 1, null, null, null);
        var task = tasks.create(userId, new CreateTaskRequest("Повтор с доски", null, null, null,
                null, null, null, null, null, rule));
        var completion = new UpdateTaskRequest(null, null, null, TaskStatus.DONE, null, null, null, null, null);

        tasks.update(userId, task.id(), completion);
        tasks.update(userId, task.id(), completion);
        tasks.complete(userId, task.id());

        assertThat(taskCount(userId)).isEqualTo(2);
    }

    @Test
    void competingPatchesKeepBothIndependentChanges() throws Exception {
        UUID userId = newUser();
        var task = tasks.createQuick(userId, request("Первоначальное название"));
        var index = new java.util.concurrent.atomic.AtomicInteger();
        concurrently(() -> {
            var patch = index.getAndIncrement() == 0
                    ? new UpdateTaskRequest("Новое название", null, null, null, null, null, null, null, null)
                    : new UpdateTaskRequest(null, "Описание", null, null, null, null, null, null, null);
            return tasks.update(userId, task.id(), patch);
        });

        var result = tasks.findById(userId, task.id());
        assertThat(result.title()).isEqualTo("Новое название");
        assertThat(result.description()).isEqualTo("Описание");
    }

    @Test
    void delayedDateSuggestionDoesNotOverwriteManualPlannedDate() {
        UUID userId = newUser();
        var task = tasks.createQuick(userId, request("Подготовить пакет документов"));
        var manualDate = java.time.OffsetDateTime.now().plusDays(1).withNano(0);
        when(dateGenerator.generate(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            tasks.update(userId, task.id(), new UpdateTaskRequest(null, null, null, null,
                    null, null, null, null, null, manualDate, null, null));
            return new PlannedDateGeneration(manualDate.plusDays(3), false, 5, 5);
        });

        assertThat(tasks.suggestPlannedDate(userId, task.id()).plannedDate()).isEqualTo(manualDate);
    }

    enum ReminderDecision { COMPLETE, SNOOZE, CANCEL }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ReminderDecision.class)
    void chainAdvanceRespectsDecisionCommittedWhileWaitingForTaskLock(ReminderDecision decision) throws Exception {
        UUID userId = newUser();
        var task = tasks.createQuick(userId, new CreateTaskRequest("Настойчивое напоминание", null, null, null,
                null, null, null, null, null, null, true));
        var now = java.time.OffsetDateTime.now().withNano(0);
        tasks.scheduleReminder(userId, task.id(), now.minusMinutes(1));
        UUID reminderId = tasks.getReminders(userId, task.id()).getFirst().id();
        var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                taskRepository.findForUpdate(task.id(), userId).orElseThrow();
                future.set(executor.submit(() -> reminders.advancePersistentChains(now)));
                waitForTaskLock();
                switch (decision) {
                    case COMPLETE -> tasks.complete(userId, task.id());
                    case SNOOZE -> tasks.snoozeReminder(userId, task.id(), reminderId, now.plusHours(1));
                    case CANCEL -> tasks.cancelReminder(userId, task.id(), reminderId);
                }
            });
            future.get().get(20, TimeUnit.SECONDS);
        }

        var pending = tasks.getReminders(userId, task.id());
        if (decision == ReminderDecision.SNOOZE) {
            assertThat(pending).singleElement().satisfies(reminder ->
                    assertThat(reminder.fireAt()).isEqualTo(now.plusHours(1)));
            UUID remainingId = pending.getFirst().id();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_notifications WHERE task_id = ? AND reminder_id <> ? AND sent = false",
                    Long.class, task.id(), remainingId)).isZero();
        } else {
            assertThat(pending).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_notifications WHERE task_id = ? AND sent = false",
                    Long.class, task.id())).isZero();
        }
    }

    private void waitForTaskLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND query LIKE '%tasks%'
                    """, Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("Планировщик не дождался блокировки задачи");
    }

    private UUID newUser() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return users.findOrCreateByTelegram(telegramId, "retries_" + telegramId, "Test", "User").id();
    }

    private long taskCount(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM tasks WHERE user_id = ?", Long.class, userId);
    }

    private CreateTaskRequest request(String title) {
        return new CreateTaskRequest(title, null, null, null, null, null, null, null, null);
    }

    private UUID proposal(UUID userId, String... payloads) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assistant_proposals
                (id, short_code, user_id, source_channel, input_kind, status, exclusive, expires_at)
                VALUES (?, ?, ?, 'WEB', 'TEXT', 'PENDING', false, NOW() + INTERVAL '1 day')
                """, id, id.toString().substring(0, 12), userId);
        for (int ordinal = 1; ordinal <= payloads.length; ordinal++) {
            jdbc.update("""
                    INSERT INTO assistant_proposal_actions
                    (id, proposal_id, ordinal, type, payload, summary, accepted)
                    VALUES (?, ?, ?, 'CREATE', CAST(? AS jsonb), 'Создать задачу', true)
                    """, UUID.randomUUID(), id, ordinal, payloads[ordinal - 1]);
        }
        return id;
    }

    private <T> List<T> concurrently(Callable<T> operation) throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> synchronizedOperation = () -> {
                start.await(5, TimeUnit.SECONDS);
                return operation.call();
            };
            var first = executor.submit(synchronizedOperation);
            var second = executor.submit(synchronizedOperation);
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
}
