package ru.taskflow.task.application.read;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.task.api.RecurrenceType;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.infrastructure.persistence.*;
import ru.taskflow.user.api.UserService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TaskReadServiceIntegrationTest.TestConfig.class)
@Testcontainers
class TaskReadServiceIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = TaskRepository.class)
    @EntityScan(basePackageClasses = TaskJpaEntity.class)
    @Import({TaskReadService.class, TaskMapperImpl.class})
    static class TestConfig {}

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        properties.add("spring.liquibase.enabled", () -> "false");
    }

    @Autowired private TaskReadService service;
    @Autowired private TestEntityManager entities;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @MockBean private UserService users;

    private final UUID user = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();
    private final ZoneId zone = ZoneId.of("Asia/Tokyo");

    @BeforeEach
    void timezones() {
        when(users.getTimezone(user)).thenReturn(zone);
        when(users.getTimezone(otherUser)).thenReturn(zone);
    }

    @Test
    void recommendationRanksOldImportantWorkBeforeFiftyFuturePlans() {
        var now = OffsetDateTime.now();
        var working = task(user, "уже начато", TaskStatus.IN_PROGRESS);
        working.setCreatedAt(now.minusDays(60)); entities.persist(working);
        var overdue = task(user, "старое просроченное", TaskStatus.TODO);
        overdue.setDeadline(now.minusDays(1)); overdue.setCreatedAt(now.minusDays(50)); entities.persist(overdue);
        var next = task(user, "ближайшее без отложения", TaskStatus.TODO);
        next.setDeadline(now.plusDays(3)); entities.persist(next);
        for (int i = 0; i < 50; i++) {
            var future = task(user, "позже " + i, TaskStatus.TODO);
            future.setPlannedDate(now.plusDays(15)); future.setDeadline(now.plusDays(20)); entities.persist(future);
        }
        flush();
        var filter = new TaskReadService.Filter("active", "all", "", "all", "recommendation", null, null, List.of());
        var result = service.page(user, filter, 0, 8);
        assertThat(result.totalElements()).isEqualTo(53);
        assertThat(result.content().subList(0, 3)).extracting(task -> task.id())
                .containsExactly(working.getId(), overdue.getId(), next.getId());
    }

    @Test
    void pagedReadLoadsOnlyVisibleTasksAndPreservesTotals() {
        var identifiers = new ArrayList<UUID>();
        var base = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 41; index++) {
            var task = task(user, "дело " + index, TaskStatus.TODO);
            task.setCreatedAt(base.plusMinutes(index));
            entities.persist(task);
            identifiers.add(task.getId());
        }
        entities.persist(task(otherUser, "чужая", TaskStatus.TODO));
        flush();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var page = service.page(user, filter("active", "all", "", "all", null, null, List.of()), 3, 4);

        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.number()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(4);
        assertThat(page.content()).extracting(response -> response.id())
                .containsExactly(identifiers.get(28), identifiers.get(27), identifiers.get(26), identifiers.get(25));
        assertThat(statistics.getEntityStatistics(TaskJpaEntity.class.getName()).getLoadCount()).isEqualTo(4);
        assertThat(service.page(user, filter("active", "all", "", "all", null, null, List.of()), 50, 4).content())
                .isEmpty();
    }

    @Test
    void searchFindsLateMatchesAndTreatsLikeMetacharactersAsLiteral() {
        for (int index = 0; index < 30; index++) {
            entities.persist(task(user, "обычная " + index, TaskStatus.TODO));
        }
        var expected = task(user, "Проверить скидку 50%_!", TaskStatus.TODO);
        expected.setCreatedAt(OffsetDateTime.parse("2020-01-01T00:00:00Z"));
        entities.persist(expected);
        entities.persist(task(user, "Проверить скидку 5000", TaskStatus.TODO));
        entities.persist(task(otherUser, expected.getTitle(), TaskStatus.TODO));
        flush();

        var result = service.page(user, filter("active", "all", "50%_!", "all", null, null, List.of()), 0, 20);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).extracting(response -> response.id()).containsExactly(expected.getId());
    }

    @Test
    void revealCannotBypassUserScopeDeletedRowsOrFilters() {
        var owned = entities.persist(task(user, "моя", TaskStatus.TODO));
        var secret = entities.persist(task(otherUser, "чужая", TaskStatus.TODO));
        var completed = entities.persist(task(user, "закончена", TaskStatus.DONE));
        var deleted = task(user, "удалена", TaskStatus.TODO);
        deleted.setDeleted(true);
        entities.persist(deleted);
        flush();

        var result = service.page(user, filter("active", "all", "", "all", null, null,
                List.of(secret.getId(), completed.getId(), deleted.getId(), owned.getId())), 0, 20);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).extracting(response -> response.id()).containsExactly(owned.getId());
    }

    @Test
    void pageContainsGroupTagsPendingRemindersAndNumericWeeklyRule() {
        var group = new GroupJpaEntity();
        group.setUserId(user);
        group.setName("учёба");
        entities.persist(group);
        var expected = task(user, "повтор", TaskStatus.TODO);
        expected.setGroup(group);
        expected.setPersistentReminder(true);
        var firstTag = new TagJpaEntity();
        firstTag.setUserId(user);
        firstTag.setName("диплом");
        expected.getTags().add(firstTag);
        entities.persist(expected);
        var rule = new RecurrenceJpaEntity();
        rule.setTask(expected);
        rule.setType(RecurrenceType.WEEKLY);
        rule.setIntervalN(2);
        rule.setDaysOfWeek("2,4");
        entities.persist(rule);
        var late = reminder(expected, ReminderStatus.PENDING, 2);
        entities.persist(late);
        var early = reminder(expected, ReminderStatus.PENDING, 1);
        early.setChainStep(0);
        entities.persist(early);
        entities.persist(reminder(expected, ReminderStatus.CANCELLED, 3));
        flush();

        var page = service.page(user, filter("active", group.getId().toString(), "", "all", null, null, List.of()), 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        var response = page.content().getFirst();
        assertThat(response.groupName()).isEqualTo("учёба");
        assertThat(response.tags()).containsExactly("диплом");
        assertThat(response.persistentReminder()).isTrue();
        assertThat(response.reminders()).extracting(reminder -> reminder.id()).containsExactly(early.getId(), late.getId());
        assertThat(response.reminders().getFirst().persistent()).isTrue();
        assertThat(response.recurrence().type()).isEqualTo(RecurrenceType.WEEKLY);
        assertThat(response.recurrence().intervalN()).isEqualTo(2);
        assertThat(response.recurrence().daysOfWeek()).containsExactly(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY);
    }

    @Test
    void todayAndWeekRespectTheUsersMidnightAndPlannedDay() {
        var midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        var inside = task(user, "перед полуночью", TaskStatus.TODO);
        inside.setDeadline(midnight.minusSeconds(1));
        entities.persist(inside);
        var boundary = task(user, "завтра", TaskStatus.TODO);
        boundary.setDeadline(midnight);
        entities.persist(boundary);
        var plannedLater = task(user, "отложено", TaskStatus.TODO);
        plannedLater.setDeadline(midnight.minusHours(1));
        plannedLater.setPlannedDate(midnight.plusDays(1));
        entities.persist(plannedLater);
        var undated = entities.persist(task(user, "без срока", TaskStatus.TODO));
        var completed = task(user, "уже выполнено", TaskStatus.DONE);
        completed.setDeadline(midnight.minusHours(1));
        entities.persist(completed);
        var weekBoundary = task(user, "через неделю", TaskStatus.TODO);
        weekBoundary.setDeadline(LocalDate.now(zone).plusDays(7).atStartOfDay(zone).toOffsetDateTime());
        entities.persist(weekBoundary);
        flush();

        var today = service.page(user, filter("all", "all", "", "today", null, null, List.of()), 0, 20);
        var week = service.page(user, filter("all", "all", "", "week", null, null, List.of()), 0, 20);

        assertThat(today.content()).extracting(response -> response.id()).containsExactlyInAnyOrder(inside.getId(), undated.getId());
        assertThat(week.content()).extracting(response -> response.id())
                .containsExactlyInAnyOrder(inside.getId(), undated.getId(), boundary.getId(), plannedLater.getId());
    }

    @Test
    void completedIntervalIsHalfOpen() {
        var since = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
        var until = since.plusDays(1);
        var first = task(user, "начало", TaskStatus.DONE);
        first.setCompletedAt(since);
        entities.persist(first);
        var boundary = task(user, "следующий день", TaskStatus.DONE);
        boundary.setCompletedAt(until);
        entities.persist(boundary);
        flush();

        var page = service.page(user, filter("DONE", "all", "", "all", since, until, List.of()), 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).extracting(response -> response.id()).containsExactly(first.getId());
    }

    @Test
    void overviewCountsOnlyThisUsersVisibleTasksAndCurrentWeek() {
        var group = new GroupJpaEntity();
        group.setUserId(user);
        group.setName("работа");
        entities.persist(group);
        entities.persist(task(user, "план", TaskStatus.TODO));
        entities.persist(task(user, "в работе", TaskStatus.IN_PROGRESS));
        var done = task(user, "готово", TaskStatus.DONE);
        done.setCompletedAt(OffsetDateTime.now());
        done.setGroup(group);
        entities.persist(done);
        var old = task(user, "раньше", TaskStatus.DONE);
        old.setCompletedAt(LocalDate.now(zone).minusDays(7).atStartOfDay(zone).toOffsetDateTime());
        old.setGroup(group);
        entities.persist(old);
        entities.persist(task(user, "отменено", TaskStatus.CANCELLED));
        entities.persist(task(otherUser, "чужая", TaskStatus.TODO));
        var deleted = task(user, "скрыта", TaskStatus.TODO);
        deleted.setDeleted(true);
        entities.persist(deleted);
        flush();

        var overview = service.overview(user);

        assertThat(overview.active()).isEqualTo(2);
        assertThat(overview.history()).isEqualTo(3);
        assertThat(overview.statuses()).containsEntry("TODO", 1L).containsEntry("DONE", 2L);
        var grouped = overview.groups().stream().filter(row -> row.groupId().equals(group.getId().toString())).findFirst().orElseThrow();
        assertThat(grouped.active()).isZero();
        assertThat(grouped.history()).isEqualTo(2);
        assertThat(grouped.weeklyDone()).isEqualTo(1);
    }

    @Test
    void invalidParametersAreRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.page(user, filter("active", "all", "", "all", null, null, List.of()), -1, 20))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.page(user, filter("active", "all", "", "all", null, null, List.of()), 0, 101))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.page(user, filter("active", "not-a-uuid", "", "all", null, null, List.of()), 0, 20))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.page(user, filter("active' OR true--", "all", "", "all", null, null, List.of()), 0, 20))
                .isInstanceOf(ResponseStatusException.class);
    }

    private TaskReadService.Filter filter(String status, String group, String query, String context,
            OffsetDateTime since, OffsetDateTime until, List<UUID> reveal) {
        return new TaskReadService.Filter(status, group, query, context, "default", since, until, reveal);
    }

    private TaskJpaEntity task(UUID owner, String title, TaskStatus status) {
        var task = new TaskJpaEntity();
        task.setUserId(owner);
        task.setTitle(title);
        task.setStatus(status);
        return task;
    }

    private ReminderJpaEntity reminder(TaskJpaEntity task, ReminderStatus status, int hours) {
        var reminder = new ReminderJpaEntity();
        reminder.setTask(task);
        reminder.setStatus(status);
        reminder.setFireAt(OffsetDateTime.now().plusHours(hours));
        return reminder;
    }

    private void flush() {
        entities.flush();
        entities.clear();
    }
}
