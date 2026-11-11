package ru.taskflow.app;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.taskflow.assistant.api.*;
import ru.taskflow.assistant.api.dto.*;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.nlp.api.*;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"app.assistant.planning-mode=BATCH_PLAN","app.notification.engagement-enabled=false"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantFlowIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    @Autowired UserService users;@Autowired TaskService tasks;@Autowired TaskOperationService operations;
    @Autowired AssistantService assistant;@Autowired JdbcTemplate jdbc;
    @MockBean NlpGatewayService model;
    @MockBean ru.taskflow.assistant.application.AssistantRateLimiter admission;
    UUID owner;
    @BeforeEach void setup() {jdbc.execute("TRUNCATE users CASCADE");owner=users.findOrCreateByTelegram(321123,"flow_test","Тест",null).id();when(admission.allow(any())).thenReturn(true);}
    @Test void newUserStartsWithPersistedDefaultsAndExplicitOptOutSurvivesOtherChanges() {
        assertThat(users.getSettings(owner).taskCheckinsEnabled()).isTrue();assertThat(users.getSettings(owner).returnNudgesEnabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT task_checkins_enabled_at IS NOT NULL AND return_nudges_enabled_at IS NOT NULL FROM user_settings WHERE user_id=?",Boolean.class,owner)).isTrue();
        users.updateSettings(owner,new UpdateSettingsRequest(null,null,null,null,null,null,null,null,null,null,null,null,false,false));
        users.updateSettings(owner,new UpdateSettingsRequest(null,null,null,null,30,null,null,null,null,null,null,null));
        assertThat(users.getSettings(owner).taskCheckinsEnabled()).isFalse();assertThat(users.getSettings(owner).returnNudgesEnabled()).isFalse();
    }
    @Test void createIsAutomaticAndUndoIsIdempotent() {
        UUID id=proposal("CREATE",null,"{\"title\":\"Подготовить пару\"}");
        var result=assistant.resolveAutomatically(owner,id);assertThat(result.applied().appliedCount()).isEqualTo(1);
        var outcome=result.applied().outcomes().getFirst();assertThat(outcome.undoId()).isNotNull();
        assertThat(tasks.findById(owner,outcome.taskId()).title()).isEqualTo("Подготовить пару");
        assertThat(assistant.resolveAutomatically(owner,id).applied().outcomes().getFirst().taskId()).isEqualTo(outcome.taskId());
        assertThat(operations.undo(owner,outcome.undoId()).undone()).isTrue();assertThat(operations.undo(owner,outcome.undoId()).undone()).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM tasks WHERE id=?",Boolean.class,outcome.taskId())).isTrue();
        assertThat(assistant.resolveAutomatically(owner,id).applied().outcomes().getFirst().undone()).isTrue();
    }
    @Test void completionRestoresActualPreviousStatusAndFutureReminders() {
        var task=task();tasks.update(owner,task.id(),new UpdateTaskRequest(null,null,null,TaskStatus.IN_PROGRESS,null,null,null,null,null));
        var before=tasks.findById(owner,task.id());var reminders=tasks.getReminders(owner,task.id());
        UUID id=proposal("COMPLETE",task.id(),"{}");var auto=assistant.resolveAutomatically(owner,id);
        assertThat(auto.applied().appliedCount()).isEqualTo(1);assertThat(tasks.findById(owner,task.id()).status()).isEqualTo(TaskStatus.DONE);
        var op=auto.applied().outcomes().getFirst().undoId();operations.undo(owner,op);
        var restored=tasks.findById(owner,task.id());assertThat(restored.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(restored.startedAt().toInstant()).isEqualTo(before.startedAt().toInstant());
        assertThat(tasks.getReminders(owner,task.id())).extracting(ReminderResponse::id).containsExactlyElementsOf(reminders.stream().map(ReminderResponse::id).toList());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE task_id=? AND NOT sent AND payload_type='TASK_REMINDER'",Integer.class,task.id())).isEqualTo(reminders.size());
    }
    @Test void newTaskEditsAndForeignUserPreventUndo() {
        var auto=assistant.resolveAutomatically(owner,proposal("CREATE",null,"{\"title\":\"Черновик\"}"));var action=auto.applied().outcomes().getFirst();
        tasks.update(owner,action.taskId(),new UpdateTaskRequest("Уже исправленное название",null,null,null,null,null,null,null,null));
        assertThatThrownBy(()->operations.undo(owner,action.undoId())).hasMessageContaining("уже изменилась");
        UUID other=users.findOrCreateByTelegram(222333,"other_flow","Другой",null).id();
        assertThatThrownBy(()->operations.undo(other,action.undoId())).hasMessageContaining("недоступно");
        assertThat(tasks.findById(owner,action.taskId()).title()).isEqualTo("Уже исправленное название");
    }
    @Test void reminderEditsAfterCreationPreventUndo() {
        var result=assistant.resolveAutomatically(owner,proposal("CREATE",null,"{\"title\":\"Черновик\"}"));var action=result.applied().outcomes().getFirst();
        tasks.scheduleReminder(owner,action.taskId(),OffsetDateTime.now().plusHours(2));
        assertThatThrownBy(()->operations.undo(owner,action.undoId())).hasMessageContaining("уже изменилась");
    }
    @Test void simultaneousUndoOnlyRestoresOnce() throws Exception {
        var result=assistant.resolveAutomatically(owner,proposal("CREATE",null,"{\"title\":\"Черновик\"}"));var action=result.applied().outcomes().getFirst();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->operations.undo(owner,action.undoId()));var b=pool.submit(()->operations.undo(owner,action.undoId()));
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_operations WHERE undone_at IS NOT NULL",Integer.class)).isEqualTo(1);
    }
    @Test void cancellationAndAmbiguityStillNeedClarification() {
        var task=task();UUID cancel=proposal("CANCEL",task.id(),"{}");assertThat(assistant.resolveAutomatically(owner,cancel).applied()).isNull();
        UUID complete=proposal("COMPLETE",task.id(),"{}");jdbc.update("UPDATE assistant_proposals SET exclusive=true,ambiguity_reason='Два прочтения' WHERE id=?",complete);
        assertThat(assistant.resolveAutomatically(owner,complete).applied()).isNull();assertThat(tasks.findById(owner,task.id()).status()).isEqualTo(TaskStatus.TODO);
    }
    @Test void expiredUndoAndNewStatusDoNotOverwriteTask() {
        var task=task();var auto=assistant.resolveAutomatically(owner,proposal("COMPLETE",task.id(),"{}"));var action=auto.applied().outcomes().getFirst();
        jdbc.update("UPDATE task_operations SET expires_at=NOW()-INTERVAL '1 minute' WHERE id=?",action.undoId());
        assertThatThrownBy(()->operations.undo(owner,action.undoId())).hasMessageContaining("Время отмены истекло");
        assertThat(tasks.findById(owner,task.id()).status()).isEqualTo(TaskStatus.DONE);
    }
    @Test void textAndTranscribedVoiceReachSameAutomaticCompletionPath() {
        for(boolean voice:List.of(false,true)) {
            var task=task();
            when(model.callWithTools(any())).thenReturn(new LlmToolResponse(List.of(),"{\"steps\":[{\"select\":{\"query\":\"Провести пару\"},\"action\":{\"type\":\"complete\"}}]}",123,30,false));
            when(model.transcribe(any())).thenReturn("пару провёл");
            Proposal parsed=voice?assistant.handleVoice(owner,new byte[]{1,2},AssistantChannel.TELEGRAM,AssistantEntryPoint.CHAT,UUID.randomUUID().toString())
                    :assistant.handleText(owner,"пару провёл",AssistantChannel.TELEGRAM,AssistantEntryPoint.CHAT,UUID.randomUUID().toString());
            var auto=assistant.resolveAutomatically(owner,parsed.id());assertThat(auto.applied()).isNotNull();assertThat(auto.applied().appliedCount()).isEqualTo(1);
            assertThat(tasks.findById(owner,task.id()).status()).isEqualTo(TaskStatus.DONE);
        }
    }
    private TaskResponse task() {return tasks.createQuick(owner,new CreateTaskRequest("Провести пару",null,TaskPriority.MEDIUM,OffsetDateTime.now().plusHours(3),null,null,List.of(),30,TaskSource.MANUAL));}
    private UUID proposal(String type,UUID task,String payload) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO assistant_proposals(id,short_code,user_id,source_channel,input_kind,status,expires_at) VALUES (?,?,?,'TELEGRAM','TEXT','PENDING',?)",
                id,id.toString().substring(0,8),owner,OffsetDateTime.now().plusDays(1));
        jdbc.update("INSERT INTO assistant_proposal_actions(id,proposal_id,ordinal,type,target_task_id,payload,summary,accepted) VALUES (?,?,1,?,?,?::jsonb,'Действие',true)",UUID.randomUUID(),id,type,task,payload);
        return id;
    }
}
