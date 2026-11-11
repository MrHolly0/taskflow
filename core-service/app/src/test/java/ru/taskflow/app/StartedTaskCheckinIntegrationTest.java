package ru.taskflow.app;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.taskflow.notify.application.EngagementServiceImpl;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="app.notification.engagement-enabled=false")
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StartedTaskCheckinIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired UserService users;@Autowired TaskService tasks;@Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    final OffsetDateTime now=OffsetDateTime.parse("2031-01-15T12:00:00+03:00");UUID owner;
    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE users CASCADE");owner=users.findOrCreateByTelegram(456789,"started_test","Тест",null).id();
        users.updateSettings(owner,new UpdateSettingsRequest(null,null,null,null,null,null,null,null,null,null,"Europe/Moscow",null,true,false));
        jdbc.update("UPDATE user_settings SET task_checkins_enabled_at=? WHERE user_id=?",now.minusDays(1),owner);
    }
    @Test void estimatedWorkGetsOneQuestionEvenWithoutDeadlineOrReminder() {
        var task=started(30,now.minusMinutes(45));plan(now.minusSeconds(1));assertThat(count()).isZero();
        plan(now);assertThat(count()).isEqualTo(1);assertThat(kind()).isEqualTo("WORK_CHECKIN");
        plan(now.plusMinutes(5));assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT task_id FROM engagement_prompts",UUID.class)).isEqualTo(task);
    }
    @Test void missingEstimateUsesOneHourAndDeadlineDoesNotCauseImmediateQuestion() {
        started(null,now.minusMinutes(59));plan(now);assertThat(count()).isZero();plan(now.plusMinutes(1));assertThat(count()).isEqualTo(1);
    }
    @Test void earlyStartIgnoresOlderFuturePlanButReschedulingAfterStartQuietsIt() {
        UUID task=started(30,now.minusMinutes(45));
        jdbc.update("UPDATE tasks SET planned_date=?,planned_date_set_at=? WHERE id=?",now.plusDays(2),now.minusHours(2),task);
        plan(now);assertThat(count()).isEqualTo(1);
        jdbc.execute("TRUNCATE engagement_prompts CASCADE");
        jdbc.update("UPDATE tasks SET planned_date_set_at=? WHERE id=?",now.minusMinutes(5),task);
        plan(now);assertThat(count()).isZero();
    }
    @Test void replyingWorkingDoesNotRestartSessionOrCreateAnotherQuestion() {
        UUID task=started(30,now.minusMinutes(45));plan(now);
        UUID prompt=jdbc.queryForObject("SELECT id FROM engagement_prompts",UUID.class);
        jdbc.update("UPDATE engagement_prompts SET sent_at=? WHERE id=?",now,prompt);
        var old=jdbc.queryForObject("SELECT started_at FROM tasks WHERE id=?",OffsetDateTime.class,task);
        var reply=new TransactionTemplate(transactions).execute(tx->service(now).reply(owner,prompt,"working"));
        assertThat(reply.outcome()).isEqualTo("working");
        assertThat(jdbc.queryForObject("SELECT started_at FROM tasks WHERE id=?",OffsetDateTime.class,task)).isEqualTo(old);
        plan(now.plusHours(2));assertThat(count()).isEqualTo(1);
    }
    @Test void oldQuestionCannotCloseRestartedWork() {
        UUID task=started(30,now.minusMinutes(45));plan(now);
        UUID prompt=jdbc.queryForObject("SELECT id FROM engagement_prompts",UUID.class);jdbc.update("UPDATE engagement_prompts SET sent_at=?",now);
        jdbc.update("UPDATE tasks SET started_at=? WHERE id=?",now.minusMinutes(5),task);
        var reply=new TransactionTemplate(transactions).execute(tx->service(now).reply(owner,prompt,"done"));
        assertThat(reply.outcome()).isEqualTo("stale");assertThat(tasks.findById(owner,task).status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }
    @Test void explicitOptOutAndClosedTasksDoNotReceiveQuestions() {
        UUID task=started(30,now.minusMinutes(45));
        users.updateSettings(owner,new UpdateSettingsRequest(null,null,null,null,null,null,null,null,null,null,null,null,false,null));
        plan(now);assertThat(count()).isZero();
        users.updateSettings(owner,new UpdateSettingsRequest(null,null,null,null,null,null,null,null,null,null,null,null,true,null));
        tasks.complete(owner,task);plan(now);assertThat(count()).isZero();
    }
    @Test void quietHoursDeferUntilMorningWithoutLosingWork() {
        var evening=now.withHour(22);started(30,evening.minusMinutes(45));
        plan(evening);assertThat(count()).isZero();plan(evening.plusDays(1).withHour(9));assertThat(count()).isEqualTo(1);
    }
    private UUID started(Integer estimate,OffsetDateTime at) {
        var task=tasks.createQuick(owner,new CreateTaskRequest("Подготовить пару",null,TaskPriority.MEDIUM,null,null,null,List.of(),estimate,TaskSource.MANUAL));
        jdbc.update("UPDATE tasks SET status='IN_PROGRESS',started_at=? WHERE id=?",at,task.id());return task.id();
    }
    private EngagementServiceImpl service(OffsetDateTime at) {
        var service=new EngagementServiceImpl(jdbc,mapper,tasks,users,Clock.fixed(at.toInstant(),at.getOffset()));
        ReflectionTestUtils.setField(service,"appUrl","https://example.invalid");return service;
    }
    private void plan(OffsetDateTime at) {new TransactionTemplate(transactions).executeWithoutResult(tx->service(at).plan());}
    private int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts",Integer.class);}
    private String kind(){return jdbc.queryForObject("SELECT kind FROM engagement_prompts LIMIT 1",String.class);}
}
