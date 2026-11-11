package ru.taskflow.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.notify.api.EngagementService;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.notify.application.EngagementServiceImpl;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.notification.engagement-enabled=false")
@Testcontainers
class EngagementIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired jakarta.persistence.EntityManager entities;
    @Autowired TaskService tasks;
    @Autowired UserService users;
    @Autowired NotificationService notifications;
    @Autowired PlatformTransactionManager transactions;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-01-15T12:00:00+03:00");

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE users CASCADE");
    }

    @Test
    void newPreferencesAreOffAndSparseUpdatesPreserveThem() {
        assertThat(context.getBeansOfType(ru.taskflow.app.scheduler.EngagementScheduler.class)).isEmpty();
        UUID user = user(false, false);
        task(user, NOW.minusHours(1));
        plan(NOW);
        assertThat(count()).isZero();
        assertThat(users.getSettings(user).taskCheckinsEnabled()).isFalse();
        assertThat(users.getSettings(user).returnNudgesEnabled()).isFalse();
        enable(user, true, true);
        users.updateSettings(user, new UpdateSettingsRequest(null, null, null, null, 30, null,
                null, null, null, null, null, null));
        assertThat(users.getSettings(user).taskCheckinsEnabled()).isTrue();
        assertThat(users.getSettings(user).returnNudgesEnabled()).isTrue();
    }

    @Test
    void asksAfterThirtyMinutesOnceAndCapsBothKindsTogether() {
        UUID user = user(true, true);
        task(user, NOW.minusMinutes(30));
        task(user, NOW.minusHours(2));
        inactive(user, NOW.minusDays(4));
        plan(NOW.minusMinutes(1));
        assertThat(count()).isEqualTo(1);
        assertThat(kind()).isEqualTo("TASK_CHECKIN");
        plan(NOW);
        assertThat(count()).isEqualTo(1);
        sent(prompt(), NOW);
        plan(NOW.plusHours(1));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void oldBacklogIsRecoveredWithoutSendingTheNextQuestionImmediately() {
        UUID user = user(true, false);
        task(user, NOW.minusMinutes(29));
        task(user, NOW.minusDays(2));
        plan(NOW);
        assertThat(count()).isEqualTo(1);
        plan(NOW.plusMinutes(1));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void excludesClosedMutedDeletedRescheduledAndRetrospectiveTasks() {
        for (String change : List.of("done", "cancelled", "deleted", "future-plan", "retrospective", "channel-off", "global-off")) {
            UUID user = user(true, false);
            UUID task = task(user, NOW.minusHours(1));
            switch (change) {
                case "done" -> tasks.complete(user, task);
                case "cancelled" -> jdbc.update("UPDATE tasks SET status = 'CANCELLED' WHERE id = ?", task);
                case "deleted" -> tasks.delete(user, task);
                case "muted" -> jdbc.update("UPDATE reminders SET status = 'CANCELLED' WHERE task_id = ?", task);
                case "future-plan" -> jdbc.update("UPDATE tasks SET planned_date = ? WHERE id = ?", NOW.plusDays(1), task);
                case "retrospective" -> jdbc.update("UPDATE tasks SET created_at = ? WHERE id = ?", NOW, task);
                case "channel-off" -> jdbc.update("UPDATE user_settings SET notify_telegram = false WHERE user_id = ?", user);
                case "global-off" -> jdbc.update("UPDATE user_settings SET notifications_enabled = false WHERE user_id = ?", user);
            }
        }
        plan(NOW);
        assertThat(count()).isZero();
    }

    @Test
    void explicitReminderNearNowHasPriorityOverAutomaticQuestion() {
        UUID user = user(true, false);
        UUID task = task(user, NOW.minusHours(1));
        tasks.scheduleReminder(user, task, NOW.plusMinutes(15));
        plan(NOW);
        assertThat(count()).isZero();
        plan(NOW.plusHours(1));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void enablingRecoversUnfinishedDeadlinesInOneGentleCheckin() {
        UUID user = user(false, false);
        task(user, NOW.minusHours(1));
        enable(user, true, false);
        jdbc.update("UPDATE user_settings SET task_checkins_enabled_at = ? WHERE user_id = ?", NOW, user);
        plan(NOW);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void waitsUntilNineInUsersTimezoneAndNeverPlansAfterTwentyOne() {
        UUID user = user(true, false);
        task(user, NOW.minusHours(5));
        plan(NOW.withHour(8).withMinute(59));
        assertThat(count()).isZero();
        plan(NOW.withHour(9));
        assertThat(count()).isEqualTo(1);
        clean();
        UUID late = user(true, false);
        task(late, NOW.withHour(20));
        plan(NOW.withHour(21));
        assertThat(count()).isZero();
        plan(NOW.plusDays(1).withHour(9));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void returnWaitsThreeDaysAndAtLeastSevenDaysBetweenMessages() {
        UUID user = user(false, true);
        inactive(user, NOW.minusDays(3).plusMinutes(1));
        plan(NOW);
        assertThat(count()).isZero();
        plan(NOW.plusMinutes(1));
        assertThat(count()).isEqualTo(1);
        assertThat(kind()).isEqualTo("RETURN_NUDGE");
        UUID prompt = prompt();
        sent(prompt, NOW.plusMinutes(1));
        plan(NOW.plusDays(6));
        assertThat(count()).isEqualTo(1);
        plan(NOW.plusDays(7).plusMinutes(1));
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void activityEndpointStoresActualActivityWithFiveMinuteWriteThrottle() {
        UUID user = user(false, true);
        var past = OffsetDateTime.now().minusDays(4);
        inactive(user, past);
        users.recordActivity(user);
        var first = jdbc.queryForObject("SELECT last_active_at FROM users WHERE id = ?", OffsetDateTime.class, user);
        assertThat(first).isAfter(past.plusDays(3));
        users.recordActivity(user);
        assertThat(jdbc.queryForObject("SELECT last_active_at FROM users WHERE id = ?", OffsetDateTime.class, user)).isEqualTo(first);
    }

    @Test
    void callbacksCheckOwnerAndConsumeAnswerOnlyOnce() {
        UUID user = user(true, false);
        UUID task = task(user, NOW.minusHours(1));
        plan(NOW);
        UUID prompt = prompt();
        sent(prompt, NOW);
        assertThat(reply(user(false, false), prompt, "done").terminal()).isFalse();
        assertThat(tasks.findById(user, task).status()).isEqualTo(TaskStatus.TODO);
        assertThat(reply(user, prompt, "done").text()).contains("завершена");
        assertThat(reply(user, prompt, "later").text()).contains("завершена");
        assertThat(tasks.findById(user, task).status()).isEqualTo(TaskStatus.DONE);
        assertThat(tasks.getReminders(user, task)).isEmpty();
    }

    @Test
    void workingChangesStatusWithoutCreatingNewReminder() {
        UUID user = user(true, false);
        UUID task = task(user, NOW.minusHours(1));
        plan(NOW);
        UUID prompt = prompt();
        sent(prompt, NOW);
        int before = tasks.getReminders(user, task).size();
        assertThat(reply(user, prompt, "working").text()).contains("в работе");
        assertThat(tasks.findById(user, task).status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(tasks.getReminders(user, task)).hasSize(before);
    }

    @Test
    void concurrentHourButtonsCreateOneReminderAndKeepDeadline() throws Exception {
        UUID user = user(true, false);
        var deadline = NOW.minusHours(1);
        UUID task = task(user, deadline);
        plan(NOW);
        UUID prompt = prompt();
        sent(prompt, NOW);
        int before = tasks.getReminders(user, task).size();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reply(user, prompt, "later"));
            var second = executor.submit(() -> reply(user, prompt, "later"));
            assertThat(first.get().text()).contains("через час");
            assertThat(second.get().text()).contains("через час");
        }
        assertThat(tasks.getReminders(user, task)).hasSize(before + 1);
        assertThat(tasks.getReminders(user, task)).anyMatch(r -> r.fireAt().isEqual(NOW.plusHours(1)));
        assertThat(tasks.findById(user, task).deadline()).isEqualTo(deadline);
    }

    @Test
    void movedDeadlineAndExpiredMessageCannotChangeTask() {
        UUID user = user(true, false);
        UUID task = task(user, NOW.minusHours(1));
        plan(NOW);
        UUID prompt = prompt();
        sent(prompt, NOW);
        tasks.update(user, task, new UpdateTaskRequest(null, null, null, null, NOW.plusDays(1), null, null, null, null));
        assertThat(reply(user, prompt, "done").text()).contains("уже изменена");
        assertThat(tasks.findById(user, task).status()).isEqualTo(TaskStatus.TODO);
        jdbc.update("UPDATE engagement_prompts SET expires_at = ? WHERE id = ?", NOW.minusSeconds(1), prompt);
        assertThat(reply(user, prompt, "done").text()).contains("неактуально");
    }

    @Test
    void disabledTelegramDoesNotPromiseHourReminder() {
        UUID user = user(true, false);
        UUID task = task(user, NOW.minusHours(1));
        plan(NOW);
        UUID prompt = prompt();
        sent(prompt, NOW);
        jdbc.update("UPDATE user_settings SET notify_telegram = false WHERE user_id = ?", user);
        int before = tasks.getReminders(user, task).size();
        assertThat(reply(user, prompt, "later").terminal()).isFalse();
        assertThat(tasks.getReminders(user, task)).hasSize(before);
        assertThat(jdbc.queryForObject("SELECT answer FROM engagement_prompts WHERE id = ?", String.class, prompt)).isNull();
    }

    @Test
    void muteDisablesOnlyReturnMessagesEvenForExpiredPrompt() {
        UUID user = user(true, true);
        inactive(user, NOW.minusDays(4));
        plan(NOW);
        UUID prompt = prompt();
        sent(prompt, NOW);
        jdbc.update("UPDATE engagement_prompts SET expires_at = ? WHERE id = ?", NOW.minusDays(1), prompt);
        assertThat(reply(user, prompt, "mute").text()).contains("выключены");
        var settings = users.getSettings(user);
        assertThat(settings.returnNudgesEnabled()).isFalse();
        assertThat(settings.taskCheckinsEnabled()).isTrue();
        assertThat(settings.notifyTelegram()).isTrue();
        assertThat(settings.notificationsEnabled()).isTrue();
    }

    @Test
    void payloadRefreshDoesNotEraseButtonsAndTransferCancelsPendingOutreach() throws Exception {
        UUID user = user(true, false);
        UUID task = task(user, NOW.minusHours(1));
        plan(NOW);
        UUID prompt = prompt();
        notifications.refreshTaskReminderDetails(user, task, "Другое название", NOW.minusHours(1));
        String payload = jdbc.queryForObject("SELECT payload::text FROM scheduled_notifications WHERE engagement_prompt_id = ?", String.class, prompt);
        assertThat(mapper.readTree(payload).path("promptId").asText()).isEqualTo(prompt.toString());
        notifications.transferOwnership(user, user(false, false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE engagement_prompt_id = ?", Integer.class, prompt)).isZero();
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NOT NULL FROM engagement_prompts WHERE id = ?", Boolean.class, prompt)).isTrue();
    }

    @Test
    void concurrentPlanningCannotDoubleEnqueue() throws Exception {
        UUID user = user(true, false);
        task(user, NOW.minusHours(1));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> plan(NOW));
            var second = executor.submit(() -> plan(NOW));
            first.get(); second.get();
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void multipleTelegramIdentitiesShareOneCadenceAndOnePrimaryDestination() {
        UUID user = user(false, true);
        inactive(user, NOW.minusDays(4));
        users.bindIdentity(user, ru.taskflow.user.api.IdentityProvider.TELEGRAM, "999000111");
        plan(NOW);
        plan(NOW);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE payload_type = 'RETURN_NUDGE'", Integer.class)).isEqualTo(1);
        assertThat(users.findExternalId(user, ru.taskflow.user.api.IdentityProvider.TELEGRAM)).contains("999000111");
    }

    @Test
    void permanentDeliveryFailureKeepsWeeklyCooldownButNotAnEndlessPendingReservation() {
        UUID user = user(false, true);
        inactive(user, NOW.minusDays(4));
        plan(NOW);
        jdbc.update("UPDATE scheduled_notifications SET failed_at = NOW(), last_error = 'TELEGRAM_HTTP_403'");
        plan(NOW.plusMinutes(1));
        plan(NOW.plusDays(1));
        assertThat(count()).isEqualTo(1);
        plan(NOW.plusDays(7));
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void fixedOffsetZoneAndInvalidLegacyZoneDoNotBreakDaytimePlanning() {
        UUID user = user(true, false);
        jdbc.update("UPDATE users SET timezone = '+05:30' WHERE id = ?", user);
        task(user, NOW.withHour(5));
        UUID broken = user(true, false);
        task(broken, NOW.withHour(5));
        jdbc.update("UPDATE users SET timezone = 'invalid/legacy' WHERE id = ?", broken);
        plan(NOW.withHour(6));
        assertThat(count()).isZero();
        plan(NOW.withHour(6).withMinute(30));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void profileSaveCannotOverwriteTheActivityTimestampWithAnOlderManagedEntity() {
        UUID user = user(false, false);
        var past = OffsetDateTime.now().minusDays(4);
        inactive(user, past);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var managed = entities.find(ru.taskflow.user.infrastructure.persistence.UserJpaEntity.class, user);
            users.recordActivity(user);
            managed.setUsername("Обновлённое имя");
            entities.flush();
        });
        assertThat(jdbc.queryForObject("SELECT last_active_at FROM users WHERE id = ?", OffsetDateTime.class, user))
                .isAfter(past.plusDays(3));
    }

    private EngagementServiceImpl service(OffsetDateTime time) {
        var service = new EngagementServiceImpl(jdbc, mapper, tasks, users, Clock.fixed(time.toInstant(), time.getOffset()));
        ReflectionTestUtils.setField(service, "appUrl", "https://example.invalid");
        return service;
    }
    private void plan(OffsetDateTime now) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> service(now).plan());
    }
    private EngagementService.Reply reply(UUID user, UUID prompt, String action) {
        return new TransactionTemplate(transactions).execute(status -> service(NOW).reply(user, prompt, action));
    }
    private UUID user(boolean checkins, boolean returns) {
        long id = java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        UUID user = users.findOrCreateByTelegram(id, "engagement_" + id, "Test", "User").id();
        enable(user, checkins, returns);
        return user;
    }
    private void enable(UUID user, boolean checkins, boolean returns) {
        users.updateSettings(user, new UpdateSettingsRequest(null, null, null, null, null, null,
                null, null, null, null, "Europe/Moscow", null, checkins, returns));
        jdbc.update("UPDATE user_settings SET task_checkins_enabled_at = ?, return_nudges_enabled_at = ? WHERE user_id = ?",
                NOW.minusDays(5), NOW.minusDays(5), user);
    }
    private UUID task(UUID user, OffsetDateTime deadline) {
        return tasks.create(user, new CreateTaskRequest("Подготовить доклад", null, null, deadline, null, null,
                List.of(), 30, TaskSource.MANUAL)).id();
    }
    private void inactive(UUID user, OffsetDateTime since) {
        jdbc.update("UPDATE users SET last_active_at = ? WHERE id = ?", since, user);
    }
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts", Integer.class); }
    private UUID prompt() { return jdbc.queryForObject("SELECT id FROM engagement_prompts LIMIT 1", UUID.class); }
    private String kind() { return jdbc.queryForObject("SELECT kind FROM engagement_prompts LIMIT 1", String.class); }
    private void sent(UUID prompt, OffsetDateTime time) {
        jdbc.update("UPDATE engagement_prompts SET sent_at = ? WHERE id = ?", time, prompt);
        jdbc.update("UPDATE scheduled_notifications SET sent = true, sent_at = ? WHERE engagement_prompt_id = ?", time, prompt);
    }
}
