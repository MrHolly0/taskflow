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
import ru.taskflow.task.api.TaskService;
import ru.taskflow.user.api.UserService;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"app.telegram.bot-token=","app.notification.engagement-enabled=false","app.assistant.planning-mode=BATCH_PLAN"})
@Import(MeetingPlanningIntegrationTest.TimeConfig.class)
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MeetingPlanningIntegrationTest {
    static final OffsetDateTime NOW=OffsetDateTime.parse("2026-11-12T14:37:00+03:00");
    static final String SOURCE="На следующей неделе во вторник мне надо быть на встрече с куратором, напомни мне за день до этого. Встреча в 14:00 на музейной 12 в аудитории 208";
    @TestConfiguration static class TimeConfig { @Bean @Primary Clock meetingClock(){return Clock.fixed(NOW.toInstant(),ZoneId.of("Europe/Moscow"));} }
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    @Autowired AssistantService assistant;@Autowired TaskService tasks;@Autowired UserService users;@Autowired JdbcTemplate jdbc;
    @Autowired ru.taskflow.telegram.application.WidgetReceiptProjection receipts;
    @MockBean NlpGatewayService model;@MockBean AssistantRateLimiter limiter;
    UUID user;
    final String complete="""
            {"steps":[{"action":{"type":"create","title":"Встреча с куратором","description":"Музейная, 12, аудитория 208",
              "deadline":"2026-11-20T14:00:00+03:00","reminder_before_deadline_minutes":1440}}]}
            """;
    LlmToolResponse response(String plan){return new LlmToolResponse(List.of(),plan,20,30,false);}
    @BeforeEach void setup(){jdbc.execute("TRUNCATE users CASCADE");user=users.findOrCreateByTelegram(742100,"meeting_test","Тест",null).id();when(limiter.allow(any())).thenReturn(true);}
    @Test void automaticCreationPersistsCorrectedCalendarAndDetailsAndReplayDoesNotDuplicateIt() {
        when(model.callWithTools(any())).thenReturn(response(complete));
        var proposal=assistant.handleText(user,SOURCE,AssistantChannel.TELEGRAM);
        var applied=assistant.resolveAutomatically(user,proposal.id()).applied();assertThat(applied.appliedCount()).isEqualTo(1);
        var task=tasks.findById(user,applied.outcomes().getFirst().taskId());
        assertThat(task.deadline().toInstant()).isEqualTo(OffsetDateTime.parse("2026-11-17T14:00:00+03:00").toInstant());
        assertThat(task.description()).contains("Музейная","12","208");
        assertThat(receipts.render(user,proposal.id(),0).description()).contains("Музейная","12","208");
        assertThat(task.reminders()).singleElement().satisfies(r->assertThat(r.fireAt().toInstant()).isEqualTo(OffsetDateTime.parse("2026-11-16T14:00:00+03:00").toInstant()));
        assertThat(assistant.resolveAutomatically(user,proposal.id()).applied()).isEqualTo(applied);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void lostDetailsAreRepairedBeforeAnythingIsCreated() {
        var incomplete=complete.replace("\"description\":\"Музейная, 12, аудитория 208\",","");
        when(model.callWithTools(any())).thenReturn(response(incomplete),response(complete));
        var proposal=assistant.handleText(user,SOURCE,AssistantChannel.TELEGRAM);
        verify(model,times(2)).callWithTools(any());
        assertThat(proposal.actions()).singleElement().satisfies(a->assertThat(a.payload().get("description")).isEqualTo("Музейная, 12, аудитория 208"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(assistant.resolveAutomatically(user,proposal.id()).applied().appliedCount()).isEqualTo(1);
    }
    @Test void persistentOmissionDoesNotSilentlyCreateAnIncompleteTask() {
        var incomplete=complete.replace("\"description\":\"Музейная, 12, аудитория 208\",","");
        when(model.callWithTools(any())).thenReturn(response(incomplete));
        var proposal=assistant.handleText(user,SOURCE,AssistantChannel.TELEGRAM);
        assertThat(proposal.actions()).isEmpty();assertThat(proposal.clarification()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void accidentalSelectorOnANewMeetingIsRepairedWithoutChangingAnExistingTask() {
        var invalid=complete.replace("{\"action\":", "{\"select\":{\"query\":\"запись\"},\"action\":");
        when(model.callWithTools(any())).thenReturn(response(invalid),response(complete));
        var proposal=assistant.handleText(user,SOURCE,AssistantChannel.TELEGRAM);
        verify(model,times(2)).callWithTools(any());assertThat(proposal.actions()).hasSize(1);
        assertThat(proposal.actions().getFirst().type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void repeatingTheMeetingShowsTheSameReminderInTheUsersTimezoneAndDoesNotDuplicateIt() {
        when(model.callWithTools(any())).thenReturn(response(complete));
        var created=assistant.handleText(user,SOURCE,AssistantChannel.TELEGRAM);
        var result=assistant.resolveAutomatically(user,created.id()).applied();
        var taskId=result.outcomes().getFirst().taskId();
        when(model.callWithTools(any())).thenReturn(response("""
                {"steps":[{"select":{"query":"куратором"},"action":{"type":"remind","reminder_at":"2026-11-16T11:00Z"}}]}
                """));
        var repeated=assistant.handleText(user,SOURCE,AssistantChannel.WEB);
        assertThat(repeated.actions()).singleElement().satisfies(action->{
            assertThat(action.type()).isEqualTo(AssistantActionType.REMIND);
            assertThat(action.targetTaskId()).isEqualTo(taskId);
            assertThat(action.summary()).endsWith("16.11 14:00");
            assertThat(OffsetDateTime.parse(action.payload().get("reminder_at").toString()).toInstant())
                    .isEqualTo(OffsetDateTime.parse("2026-11-16T14:00+03:00").toInstant());
        });
        jdbc.update("UPDATE assistant_proposal_actions SET summary='Напомнить — Встреча с куратором → 16.11 11:00' WHERE proposal_id=?",repeated.id());
        assertThat(assistant.findById(user,repeated.id()).actions().getFirst().summary()).endsWith("16.11 14:00");
        var action=repeated.actions().getFirst();
        var changedPayload=new java.util.HashMap<>(action.payload());changedPayload.put("reminder_at","2026-11-16T12:00Z");
        var changed=new ru.taskflow.assistant.api.dto.ProposedAction(action.ordinal(),action.type(),action.targetTaskId(),changedPayload,action.summary(),action.accepted());
        assertThatThrownBy(()->assistant.apply(user,repeated.id(),List.of(changed))).hasMessageContaining("Разбор изменился");
        var oldDisplay=new ru.taskflow.assistant.api.dto.ProposedAction(action.ordinal(),action.type(),action.targetTaskId(),action.payload(),
                "Напомнить — Встреча с куратором → 16.11 11:00",action.accepted());
        var applied=assistant.apply(user,repeated.id(),List.of(oldDisplay));
        assertThat(applied.appliedCount()).isEqualTo(1);
        assertThat(applied.outcomes().getFirst().summary()).endsWith("16.11 14:00");
        assertThat(assistant.result(user,repeated.id()).outcomes().getFirst().summary()).endsWith("16.11 14:00");
        assertThat(assistant.apply(user,repeated.id())).isEqualTo(applied);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(tasks.getReminders(user,taskId)).filteredOn(r->r.status().equals("PENDING")).hasSize(1);
        users.updateSettings(user,new ru.taskflow.user.api.dto.UpdateSettingsRequest(null,null,null,null,null,null,null,null,null,null,"Asia/Tokyo",null));
        assertThat(assistant.result(user,repeated.id()).outcomes().getFirst().summary()).endsWith("16.11 20:00");
    }
}
