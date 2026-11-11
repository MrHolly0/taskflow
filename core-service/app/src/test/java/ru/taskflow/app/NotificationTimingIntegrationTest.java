package ru.taskflow.app;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.taskflow.notify.api.EngagementService;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"app.telegram.bot-token=","app.notification.engagement-enabled=false"})
@Import(NotificationTimingIntegrationTest.TimeConfig.class)
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationTimingIntegrationTest {
    static class Time extends Clock {
        Instant value;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
        void at(String value){this.value=OffsetDateTime.parse(value).toInstant();}
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary Time time(){var t=new Time();t.at("2030-11-11T22:40:00+03:00");return t;} }
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    @Autowired ru.taskflow.shared.widgets.CheckinBatchView batches;
    @Autowired Time clock;@Autowired JdbcTemplate jdbc;@Autowired UserService users;@Autowired TaskService tasks;@Autowired EngagementService engagement;
    UUID user;
    OffsetDateTime deadline=OffsetDateTime.parse("2030-11-11T23:00:00+03:00");
    @BeforeEach void setup(){jdbc.execute("TRUNCATE users CASCADE");clock.at("2030-11-11T22:40:00+03:00");user=users.findOrCreateByTelegram(987321,"timing_test","Тест",null).id();}
    TaskResponse task(){var created=tasks.createQuick(user,new CreateTaskRequest("Помыть кухню",null,TaskPriority.MEDIUM,deadline,null,null,List.of(),30,TaskSource.MANUAL,null,false,null,true,deadline.minusMinutes(19)));return tasks.findById(user,created.id());}
    int count(String kind){return jdbc.queryForObject("SELECT COUNT(*) FROM engagement_prompts WHERE kind=?",Integer.class,kind);}
    void quiet(boolean enabled,String start,String end){users.updateSettings(user,new UpdateSettingsRequest(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,enabled,start,end));}
    @Test void snoozeRecordsAnOutcomeAndDoesNotBlockTheDeadlineOrCreateAnExtraPassiveQuestion() {
        var task=task();tasks.snoozeReminder(user,task.id(),task.reminders().getFirst().id(),deadline.plusMinutes(40));
        assertThat(jdbc.queryForObject("SELECT purpose FROM reminders WHERE task_id=? AND status='PENDING'",String.class,task.id())).isEqualTo("OUTCOME_CHECK");
        assertThat(jdbc.queryForObject("SELECT deadline_snapshot FROM reminders WHERE task_id=? AND status='PENDING'",OffsetDateTime.class,task.id()).isEqual(deadline)).isTrue();
        clock.at("2030-11-11T23:00:05+03:00");engagement.plan();engagement.plan();
        assertThat(count("DEADLINE_CHECKIN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT explicit_timing FROM engagement_prompts WHERE kind='DEADLINE_CHECKIN'",Boolean.class)).isTrue();
        var prompt=jdbc.queryForObject("SELECT id FROM engagement_prompts WHERE kind='DEADLINE_CHECKIN'",UUID.class);
        jdbc.update("UPDATE engagement_prompts SET sent_at=? WHERE id=?",OffsetDateTime.now(clock),prompt);
        clock.at("2030-11-11T23:05:00+03:00");tasks.update(user,task.id(),new UpdateTaskRequest(null,null,null,TaskStatus.IN_PROGRESS,null,null,null,null,null));
        quiet(false,"21:00","09:00");clock.at("2030-11-11T23:30:00+03:00");engagement.plan();
        assertThat(count("WORK_CHECKIN")).isZero();assertThat(count("TASK_CHECKIN")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE task_id=? AND payload_type='TASK_REMINDER'",Integer.class,task.id())).isEqualTo(1);
    }
    @Test void severalDeadlinesUseOneBatchAndOldOverdueTasksDoNotGenerateALateStartQuestion() {
        task();task();task();clock.at("2030-11-11T23:00:05+03:00");engagement.plan();engagement.plan();
        assertThat(count("CHECKIN_BATCH")).isEqualTo(1);assertThat(count("DEADLINE_CHECKIN")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE payload_type='CHECKIN_BATCH'",Integer.class)).isEqualTo(1);
        jdbc.execute("TRUNCATE tasks CASCADE");clock.at("2030-11-11T22:40:00+03:00");task();clock.at("2030-11-12T03:00:00+03:00");engagement.plan();
        assertThat(count("DEADLINE_CHECKIN")).isZero();
    }
    @Test void movingTheDeadlineInvalidatesThePromisedOutcomeButPreservesAnIndependentReminder() {
        var task=task();tasks.snoozeReminder(user,task.id(),task.reminders().getFirst().id(),deadline.plusMinutes(40));
        tasks.scheduleReminder(user,task.id(),deadline.minusMinutes(10));
        tasks.update(user,task.id(),new UpdateTaskRequest(null,null,null,null,deadline.plusDays(1),null,null,null,null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE task_id=? AND purpose='OUTCOME_CHECK' AND status='PENDING'",Integer.class,task.id())).isZero();
        assertThat(tasks.getReminders(user,task.id()).stream().filter(reminder->reminder.status().equals("PENDING")).map(reminder->reminder.fireAt().toInstant())).contains(deadline.minusMinutes(10).toInstant());
    }
    @Test void aDeliveredReminderAtTheDeadlineReplacesTheDeadlineCardAndPassiveQuestion() {
        var task=task();tasks.scheduleReminder(user,task.id(),deadline);quiet(false,"21:00","09:00");
        clock.at("2030-11-11T23:00:05+03:00");engagement.plan();assertThat(count("DEADLINE_CHECKIN")).isZero();
        jdbc.update("UPDATE scheduled_notifications SET sent=true,sent_at=? WHERE task_id=? AND fire_at=?",OffsetDateTime.now(clock),task.id(),deadline);
        clock.at("2030-11-11T23:31:00+03:00");engagement.plan();assertThat(count("TASK_CHECKIN")).isZero();
    }
    @Test void aPromisedOutcomeMakesOnlyItsOwnPassiveBatchPageInactive() {
        var first=task();var second=task();quiet(false,"21:00","09:00");
        clock.at("2030-11-12T09:00:00+03:00");engagement.plan();
        var batch=jdbc.queryForObject("SELECT id FROM engagement_prompts WHERE kind='CHECKIN_BATCH'",UUID.class);
        var before=batches.items(user,batch);assertThat(before).hasSize(2).allMatch(item->item.pending());
        tasks.scheduleReminder(user,first.id(),OffsetDateTime.now(clock).plusHours(2));
        assertThat(batches.items(user,batch)).filteredOn(item->item.taskId().equals(first.id())).allMatch(item->!item.pending());
        assertThat(batches.items(user,batch)).filteredOn(item->item.taskId().equals(second.id())).allMatch(item->item.pending());
    }
    @Test void changingQuietHoursWakesOnlyQuietDeferralsAndPreservesTransportBackoff() {
        var quietQueue=UUID.randomUUID();var retryQueue=UUID.randomUUID();var until=deadline.plusHours(10);
        for(var queue:List.of(quietQueue,retryQueue)) {
            var prompt=UUID.randomUUID();
            jdbc.update("INSERT INTO engagement_prompts(id,user_id,kind,created_at,expires_at) VALUES (?,?,'RETURN_NUDGE',?,?)",prompt,user,OffsetDateTime.now(clock),deadline.plusDays(1));
            jdbc.update("INSERT INTO scheduled_notifications(id,user_id,channel,destination,fire_at,payload_type,payload,engagement_prompt_id,next_attempt_at,quiet_deferred_until) VALUES (?,?,'TELEGRAM','987321',?,'RETURN_NUDGE','{}',?,?,?)",queue,user,OffsetDateTime.now(clock),prompt,until,queue.equals(quietQueue)?until:null);
        }
        quiet(false,"21:00","09:00");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM scheduled_notifications WHERE id=?",OffsetDateTime.class,quietQueue).isEqual(OffsetDateTime.now(clock))).isTrue();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM scheduled_notifications WHERE id=?",OffsetDateTime.class,retryQueue).isEqual(until)).isTrue();
        jdbc.update("UPDATE scheduled_notifications SET next_attempt_at=?,quiet_deferred_until=? WHERE id=?",until,until,quietQueue);
        users.updateSettings(user,new UpdateSettingsRequest(null,null,null,null,null,null,null,null,null,null,"Asia/Tokyo",null));
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM scheduled_notifications WHERE id=?",OffsetDateTime.class,quietQueue).isEqual(OffsetDateTime.now(clock))).isTrue();
        assertThat(users.getSettings(user).timezone()).isEqualTo("Asia/Tokyo");
    }
    @Test void quietHoursAreEditableAndAnInvalidPairDoesNotPartiallySave() {
        quiet(true,"09:00","18:00");assertThat(users.getSettings(user).quietHoursStart()).isEqualTo("09:00");
        assertThatThrownBy(()->quiet(false,"12:00","12:00")).hasMessageContaining("разные");
        assertThat(users.getSettings(user).quietHoursEnabled()).isTrue();assertThat(users.getSettings(user).quietHoursStart()).isEqualTo("09:00");
        quiet(false,"09:00","18:00");assertThat(users.getSettings(user).quietHoursEnabled()).isFalse();
    }
}
