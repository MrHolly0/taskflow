package ru.taskflow.task.infrastructure.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.task.api.RecurrenceType;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TaskRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = TaskRepository.class)
    @EntityScan(basePackageClasses = TaskJpaEntity.class)
    static class TestConfig {
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");
    }

    @Autowired
    private TaskRepository repository;

    @Autowired
    private RecurrenceRepository recurrenceRepository;

    @Autowired
    private TestEntityManager entityManager;

    // Ловим предупреждение Hibernate прямо в тесте, а не полагаемся на
    // осмотр логов вручную — HHH90003004 значит, что запрос со страничной
    // разбивкой и JOIN FETCH коллекции резался в памяти, а не в SQL (блок Б).
    private ListAppender<ILoggingEvent> hibernateLog;

    @BeforeEach
    void attachHibernateLogCapture() {
        hibernateLog = new ListAppender<>();
        hibernateLog.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.hibernate")).addAppender(hibernateLog);
    }

    @AfterEach
    void detachHibernateLogCapture() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.hibernate")).detachAppender(hibernateLog);
    }

    private void assertNoInMemoryPaginationWarning() {
        boolean warned = hibernateLog.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .anyMatch(e -> e.getFormattedMessage().contains("HHH90003004"));
        assertThat(warned)
                .overridingErrorMessage("Hibernate резал страницу в памяти (HHH90003004) вместо SQL LIMIT/OFFSET")
                .isFalse();
    }

    @Test
    void search_findsMatchInDescription() {
        var userId = UUID.randomUUID();
        var task = newTask(userId, "покупки", "купить корм коту");
        repository.saveAndFlush(task);
        entityManager.clear();

        var found = repository.search(userId, "корм", false, PageRequest.of(0, 20));

        assertThat(found).extracting(TaskJpaEntity::getTitle).containsExactly("покупки");
    }

    @Test
    void search_excludesDoneTaskUnlessIncludeCompleted() {
        var userId = UUID.randomUUID();
        var task = newTask(userId, "позвонить Марку", "договориться о встрече");
        task.setStatus(TaskStatus.DONE);
        repository.saveAndFlush(task);
        entityManager.clear();

        var withoutCompleted = repository.search(userId, "Марку", false, PageRequest.of(0, 20));
        var withCompleted = repository.search(userId, "Марку", true, PageRequest.of(0, 20));

        assertThat(withoutCompleted).isEmpty();
        assertThat(withCompleted).extracting(TaskJpaEntity::getTitle).containsExactly("позвонить Марку");
    }

    @Test
    void findFocusTasks_returnsTaskWithPastPlannedDate_evenWithoutDeadline() {
        var userId = UUID.randomUUID();
        var endOfToday = OffsetDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(0);
        var task = newTask(userId, "позвонить юристу", null);
        task.setPlannedDate(endOfToday.minusDays(3));
        repository.saveAndFlush(task);
        entityManager.clear();

        var found = repository.findFocusTasks(userId, endOfToday);

        assertThat(found).extracting(TaskJpaEntity::getTitle).containsExactly("позвонить юристу");
    }

    @Test
    void findFocusTasks_excludesTaskSnoozedToFutureDay() {
        var userId = UUID.randomUUID();
        var endOfToday = OffsetDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(0);
        var task = newTask(userId, "отложенная задача", null);
        task.setPlannedDate(endOfToday.plusDays(1));
        repository.saveAndFlush(task);
        entityManager.clear();

        var found = repository.findFocusTasks(userId, endOfToday);

        assertThat(found).isEmpty();
    }

    @Test
    void findFocusTasks_stillIncludesTaskWithoutPlannedDateOrDeadline() {
        var userId = UUID.randomUUID();
        var endOfToday = OffsetDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(0);
        var task = newTask(userId, "без срока и без дня", null);
        repository.saveAndFlush(task);
        entityManager.clear();

        var found = repository.findFocusTasks(userId, endOfToday);

        assertThat(found).extracting(TaskJpaEntity::getTitle).containsExactly("без срока и без дня");
    }

    @Test
    void findFocusTasks_excludesCancelledTask() {
        var userId = UUID.randomUUID();
        var endOfToday = OffsetDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(0);
        var task = newTask(userId, "отменённая задача", null);
        task.setDeadline(endOfToday.minusHours(2));
        task.setStatus(TaskStatus.CANCELLED);
        repository.saveAndFlush(task);
        entityManager.clear();

        var found = repository.findFocusTasks(userId, endOfToday);

        assertThat(found).isEmpty();
    }

    @Test
    void findUpcomingFocusTasks_excludesCancelledTask() {
        var userId = UUID.randomUUID();
        var endOfToday = OffsetDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(0);
        var task = newTask(userId, "отменённая задача на неделю", null);
        task.setDeadline(endOfToday.plusDays(7));
        task.setStatus(TaskStatus.CANCELLED);
        repository.saveAndFlush(task);
        entityManager.clear();

        var found = repository.findUpcomingFocusTasks(userId, endOfToday);

        assertThat(found).isEmpty();
    }

    @Test
    void recurrenceRule_roundTripsThroughRealSchema() {
        var userId = UUID.randomUUID();
        var task = newTask(userId, "полить цветы", null);
        repository.saveAndFlush(task);

        var recurrence = new RecurrenceJpaEntity();
        recurrence.setTask(task);
        recurrence.setType(RecurrenceType.WEEKLY);
        recurrence.setIntervalN(2);
        recurrence.setDaysOfWeek("2,4");
        recurrence.setEndsAt(OffsetDateTime.parse("2027-01-01T00:00:00Z"));
        recurrenceRepository.saveAndFlush(recurrence);
        entityManager.clear();

        var loaded = recurrenceRepository.findById(task.getId()).orElseThrow();
        assertThat(loaded.getType()).isEqualTo(RecurrenceType.WEEKLY);
        assertThat(loaded.getIntervalN()).isEqualTo(2);
        assertThat(loaded.getDaysOfWeek()).isEqualTo("2,4");
        assertThat(loaded.getEndsAt()).isEqualTo(OffsetDateTime.parse("2027-01-01T00:00:00Z"));
    }

    private TaskJpaEntity newTask(UUID userId, String title, String description) {
        var task = new TaskJpaEntity();
        task.setUserId(userId);
        task.setTitle(title);
        task.setDescription(description);
        return task;
    }

    // --- Блок Б: постраничная выборка с JOIN FETCH коллекции ---

    private TaskJpaEntity taskWithTag(UUID userId, String title, String tagName) {
        var task = newTask(userId, title, null);
        var tag = new TagJpaEntity();
        tag.setUserId(userId);
        tag.setName(tagName);
        task.getTags().add(tag);
        return task;
    }

    @Test
    void findAllWithFilter_returnsPageOfRequestedSize_withMoreTasksThanPageSize() {
        var userId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            repository.saveAndFlush(taskWithTag(userId, "задача " + i, "срочное"));
        }
        entityManager.clear();

        var page = repository.findAllWithFilter(userId, null, null, null, null, PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertNoInMemoryPaginationWarning();
    }

    @Test
    void findAllWithFilter_preservesSortOrder() {
        var userId = UUID.randomUUID();
        repository.saveAndFlush(taskWithTag(userId, "в", "метка"));
        repository.saveAndFlush(taskWithTag(userId, "б", "метка"));
        repository.saveAndFlush(taskWithTag(userId, "а", "метка"));
        entityManager.clear();

        var pageable = PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("title").ascending());
        var page = repository.findAllWithFilter(userId, null, null, null, null, pageable);

        assertThat(page.getContent()).extracting(TaskJpaEntity::getTitle).containsExactly("а", "б", "в");
    }

    @Test
    void findAllWithFilter_filtersByGroup() {
        var userId = UUID.randomUUID();
        var group = new GroupJpaEntity();
        group.setUserId(userId);
        group.setName("работа");
        entityManager.persistAndFlush(group);

        var inGroup = taskWithTag(userId, "в группе", "метка");
        inGroup.setGroup(group);
        repository.saveAndFlush(inGroup);
        repository.saveAndFlush(taskWithTag(userId, "без группы", "метка"));
        entityManager.clear();

        var page = repository.findAllWithFilter(userId, group.getId(), null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TaskJpaEntity::getTitle).containsExactly("в группе");
    }

    @Test
    void findAllWithFilter_filtersByStatus() {
        var userId = UUID.randomUUID();
        var done = taskWithTag(userId, "выполнена", "метка");
        done.setStatus(TaskStatus.DONE);
        repository.saveAndFlush(done);
        repository.saveAndFlush(taskWithTag(userId, "в работе", "метка"));
        entityManager.clear();

        var page = repository.findAllWithFilter(userId, null, TaskStatus.DONE, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TaskJpaEntity::getTitle).containsExactly("выполнена");
    }

    @Test
    void findAllWithFilter_filtersByPriority() {
        var userId = UUID.randomUUID();
        var urgent = taskWithTag(userId, "срочная", "метка");
        urgent.setPriority(TaskPriority.URGENT);
        repository.saveAndFlush(urgent);
        repository.saveAndFlush(taskWithTag(userId, "обычная", "метка"));
        entityManager.clear();

        var page = repository.findAllWithFilter(userId, null, null, TaskPriority.URGENT, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TaskJpaEntity::getTitle).containsExactly("срочная");
    }

    @Test
    void findAllWithFilter_filtersByTag() {
        var userId = UUID.randomUUID();
        repository.saveAndFlush(taskWithTag(userId, "с меткой", "важное"));
        repository.saveAndFlush(taskWithTag(userId, "с другой меткой", "неважное"));
        entityManager.clear();

        var page = repository.findAllWithFilter(userId, null, null, null, "важное", PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TaskJpaEntity::getTitle).containsExactly("с меткой");
    }

    @Test
    void findAllWithFilter_returnsGroupAndTagsOnEachTask() {
        var userId = UUID.randomUUID();
        var group = new GroupJpaEntity();
        group.setUserId(userId);
        group.setName("дом");
        entityManager.persistAndFlush(group);

        var task = taskWithTag(userId, "полить цветы", "быт");
        task.setGroup(group);
        repository.saveAndFlush(task);
        entityManager.clear();

        var page = repository.findAllWithFilter(userId, null, null, null, null, PageRequest.of(0, 10));

        var loaded = page.getContent().get(0);
        assertThat(loaded.getGroup().getName()).isEqualTo("дом");
        assertThat(loaded.getTags()).extracting(TagJpaEntity::getName).containsExactly("быт");
    }

    @Test
    void findAllWithFilter_emptyPage_doesNotFailOnSecondQuery() {
        var userId = UUID.randomUUID();

        var page = repository.findAllWithFilter(userId, null, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void search_doesNotTriggerHibernateInMemoryPaginationWarning() {
        var userId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            repository.saveAndFlush(taskWithTag(userId, "поручение " + i, "метка"));
        }
        entityManager.clear();

        List<TaskJpaEntity> found = repository.search(userId, "поручение", false, PageRequest.of(0, 2));

        assertThat(found).hasSize(2);
        assertNoInMemoryPaginationWarning();
    }
}
