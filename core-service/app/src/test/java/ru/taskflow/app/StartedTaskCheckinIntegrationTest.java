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
    @Autowired ru.taskflow.shared.widgets.WidgetStore widgets;
    @Autowired ru.taskflow.shared.widgets.CheckinBatchView batches;
    @Autowired ru.taskflow.telegram.application.WidgetActions widgetActions;
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
    @Test void severalMissedDeadlinesAndAbandonedWorkCreateOneMessageAfterDowntime() {
        for(int i=0;i<4;i++) overdue(now.minusDays(3+i));
        UUID work=started(null,now.minusDays(5));
        jdbc.update("UPDATE user_settings SET task_checkins_enabled_at=? WHERE user_id=?",now,owner);
        plan(now);plan(now.plusMinutes(1));
        UUID root=batch();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts WHERE batch_id=?",Integer.class,root)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE payload_type='CHECKIN_BATCH'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE payload_type IN ('TASK_CHECKIN','WORK_CHECKIN')",Integer.class)).isZero();
    }
    @Test void unsentExpiredBatchCanBeRecoveredWithoutLosingItsTasks() {
        overdue(now.minusDays(3));overdue(now.minusDays(4));plan(now);
        UUID old=batch();plan(now.plusDays(8));
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NOT NULL FROM engagement_prompts WHERE id=?",Boolean.class,old)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts WHERE batch_id IS NOT NULL AND cancelled_at IS NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts WHERE kind='CHECKIN_BATCH' AND cancelled_at IS NULL",Integer.class)).isEqualTo(1);
    }
    @Test void batchPagingAnswersOnlyTheCurrentTaskAndStaleTapsDoNotCloseTheNextOne() {
        overdue(now.minusDays(3));overdue(now.minusDays(4));plan(now);UUID root=batch();
        jdbc.update("UPDATE engagement_prompts SET sent_at=?",now);
        var c=widgets.reserve(owner,"CHECKINS",root,null,null,456789,batches.render(owner,root,0));
        widgets.bind(c.id(),100,"WIDGET");c=widgets.find(c.id(),owner).orElseThrow();
        var current=batches.item(owner,root,0);String key=c.state().key()+":done";
        var next=widgetActions.act(owner,456789,100,key);
        assertThat(tasks.findById(owner,current.taskId()).status()).isEqualTo(TaskStatus.DONE);
        assertThat(next.terminal()).isFalse();assertThat(next.page()).isEqualTo(1);
        assertThat(widgetActions.act(owner,456789,100,key)).isEqualTo(next);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE status='DONE'",Integer.class)).isEqualTo(1);
        var finalState=widgetActions.act(owner,456789,100,next.key()+":working");
        assertThat(finalState.terminal()).isTrue();assertThat(finalState.heading()).isEqualTo("Всё разобрали!");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts WHERE batch_id=? AND answer IS NOT NULL",Integer.class,root)).isEqualTo(2);
    }
    @Test void aChangedTaskInTheBatchCannotBeCompletedThroughTheOldQuestion() {
        overdue(now.minusDays(3));overdue(now.minusDays(4));plan(now);UUID root=batch();
        jdbc.update("UPDATE engagement_prompts SET sent_at=?",now);
        var c=widgets.reserve(owner,"CHECKINS",root,null,null,456789,batches.render(owner,root,0));
        widgets.bind(c.id(),100,"WIDGET");c=widgets.find(c.id(),owner).orElseThrow();
        var item=batches.item(owner,root,0);jdbc.update("UPDATE tasks SET deadline=? WHERE id=?",now.plusDays(1),item.taskId());
        var state=widgetActions.act(owner,456789,100,c.state().key()+":done");
        assertThat(state.status()).isEqualTo("Дело уже изменилось");
        assertThat(tasks.findById(owner,item.taskId()).status()).isEqualTo(TaskStatus.TODO);
        assertThatThrownBy(()->widgetActions.act(owner,999,100,state.key()+":done")).hasMessageContaining("недоступна");
    }
    @Test void anUnstartedTaskWithoutDeadlineDoesNotBreakTheBacklogScan() {
        var loose=tasks.createQuick(owner,new CreateTaskRequest("Без срока",null,TaskPriority.MEDIUM,null,null,null,List.of(),null,TaskSource.MANUAL));
        jdbc.update("UPDATE tasks SET created_at=? WHERE id=?",now.minusDays(3),loose.id());
        UUID due=overdue(now.minusDays(2));plan(now);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT task_id FROM engagement_prompts",UUID.class)).isEqualTo(due);
    }
    @Test void largeBacklogIsBoundedAndTheRemainderIsKeptForTheNextDay() {
        for(int i=0;i<60;i++) overdue(now.minusDays(2+i));
        plan(now);UUID first=batch();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts WHERE batch_id=?",Integer.class,first)).isEqualTo(50);
        jdbc.update("UPDATE engagement_prompts SET sent_at=?",now);
        jdbc.update("UPDATE scheduled_notifications SET sent=true WHERE engagement_prompt_id=?",first);
        plan(now.plusHours(1));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts WHERE kind='CHECKIN_BATCH'",Integer.class)).isEqualTo(1);
        plan(now.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT task_id) FROM engagement_prompts WHERE batch_id IS NOT NULL",Integer.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE payload_type='CHECKIN_BATCH'",Integer.class)).isEqualTo(2);
    }
    private UUID batch() { return jdbc.queryForObject("SELECT id FROM engagement_prompts WHERE kind='CHECKIN_BATCH' AND cancelled_at IS NULL",UUID.class); }
    private UUID overdue(OffsetDateTime deadline) {
        var task=tasks.createQuick(owner,new CreateTaskRequest("Провести пару",null,TaskPriority.MEDIUM,null,null,null,List.of(),null,TaskSource.MANUAL));
        jdbc.update("UPDATE tasks SET deadline=?,created_at=? WHERE id=?",deadline,deadline.minusDays(1),task.id());return task.id();
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
