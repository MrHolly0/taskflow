package ru.taskflow.app;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.taskflow.assistant.api.*;
import ru.taskflow.assistant.application.AssistantRateLimiter;
import ru.taskflow.nlp.api.*;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"app.telegram.bot-token=","app.notification.engagement-enabled=false","app.assistant.planning-mode=BATCH_PLAN"})
@Import(MultipleReminderIntegrationTest.TimeConfig.class)
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MultipleReminderIntegrationTest {
    static final OffsetDateTime NOW=OffsetDateTime.parse("2030-11-11T21:11:31+03:00");
    static final String SOURCE="Напомни через 10 минут, что мне в 23:00 надо помыть кухню. Дополнительно напомни за 10 минут до 23:00";
    @TestConfiguration static class TimeConfig { @Bean @Primary Clock reminderClock(){return Clock.fixed(NOW.toInstant(),ZoneId.of("Europe/Moscow"));} }
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    @Autowired AssistantService assistant;@Autowired TaskService tasks;@Autowired TaskOperationService operations;@Autowired UserService users;@Autowired JdbcTemplate jdbc;
    @Autowired ru.taskflow.telegram.application.WidgetProposalProjection widget;
    @MockBean NlpGatewayService model;@MockBean AssistantRateLimiter limiter;
    UUID user;
    @BeforeEach void setup(){jdbc.execute("TRUNCATE users CASCADE");user=users.findOrCreateByTelegram(321987,"reminders_test","Тест",null).id();when(limiter.allow(any())).thenReturn(true);}
    void reply(){when(model.callWithTools(any())).thenReturn(new LlmToolResponse(List.of(),"""
            {"steps":[{"action":{"type":"create","title":"Помыть кухню","deadline":"2030-11-11T23:00:00+03:00",
            "reminder_after_minutes":10,"reminder_before_deadline_minutes":10}}]}
            """,20,30,false));}
    @Test void creationIsAutomaticAndBothRemindersAreIncludedInUndoAndReplay() {
        reply();var proposal=assistant.handleText(user,SOURCE,AssistantChannel.TELEGRAM);assertThat(proposal.clarification()).isNull();assertThat(proposal.actions()).hasSize(1);
        var applied=assistant.resolveAutomatically(user,proposal.id()).applied();assertThat(applied.appliedCount()).isEqualTo(1);
        var action=applied.outcomes().getFirst();var task=tasks.findById(user,action.taskId());
        assertThat(task.title()).isEqualTo("Помыть кухню");assertThat(task.deadline().toInstant()).isEqualTo(NOW.withHour(23).withMinute(0).withSecond(0).toInstant());
        assertThat(task.reminders().stream().map(r->r.fireAt().toInstant()).toList()).containsExactly(NOW.plusMinutes(10).toInstant(),NOW.withHour(22).withMinute(50).withSecond(0).toInstant());
        assertThat(assistant.resolveAutomatically(user,proposal.id()).applied().outcomes().getFirst().taskId()).isEqualTo(task.id());
        assertThat(tasks.getReminders(user,task.id())).hasSize(2);
        operations.undo(user,action.undoId());
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM tasks WHERE id=?",Boolean.class,task.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE task_id=?",Integer.class,task.id())).isZero();
    }
    @Test void aPastTimeRejectsTheWholeCreationWithoutSavingHalfTheRequest() {
        var request=new CreateTaskRequest("Не создавать частично",null,TaskPriority.MEDIUM,NOW.plusDays(1),null,null,List.of(),null,TaskSource.MANUAL,null,false,null,true,null,List.of(NOW.plusHours(1),NOW.minusMinutes(1)));
        assertThatThrownBy(()->tasks.createQuick(user,request)).hasMessageContaining("прошло");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void previewUnitesScalarAndListAndMatchesTheSavedTimes() throws Exception {
        reply();var proposal=assistant.handleText(user,SOURCE,AssistantChannel.WEB);
        jdbc.update("UPDATE assistant_proposal_actions SET payload=jsonb_set(payload,'{reminder_at}',to_jsonb(?::text)) WHERE proposal_id=?",NOW.plusMinutes(30).toString(),proposal.id());
        proposal=assistant.findById(user,proposal.id());
        var row=widget.render(user,proposal,0).rows().stream().filter(r->r.getFirst().equals("Напомнить")).findFirst().orElseThrow();
        assertThat(row.get(1)).isEqualTo("Сегодня, 21:21, 21:41, 22:50");
        var result=assistant.resolveAutomatically(user,proposal.id()).applied();
        assertThat(tasks.getReminders(user,result.outcomes().getFirst().taskId())).hasSize(3);
    }
    @Test void editingOneTimePreservesTheOtherAndLegacySingleEditCannotDropTheList() {
        reply();var proposal=assistant.handleText(user,SOURCE,AssistantChannel.WEB);int ordinal=proposal.actions().getFirst().ordinal();
        assertThatThrownBy(()->assistant.updateActionReminder(user,proposal.id(),ordinal,NOW.plusHours(1))).hasMessageContaining("несколько");
        assistant.updateActionReminders(user,proposal.id(),ordinal,List.of(NOW.plusMinutes(20),NOW.withHour(22).withMinute(50).withSecond(0)));
        var result=assistant.resolveAutomatically(user,proposal.id()).applied();
        assertThat(tasks.getReminders(user,result.outcomes().getFirst().taskId()).stream().map(r->r.fireAt().toInstant()).toList()).containsExactly(NOW.plusMinutes(20).toInstant(),NOW.withHour(22).withMinute(50).withSecond(0).toInstant());
    }
}
