package ru.taskflow.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.shared.widgets.*;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.telegram.application.WidgetActions;
import ru.taskflow.telegram.application.WidgetProposalProjection;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TelegramWidgetsIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired TaskService tasks;
    @Autowired WidgetStore store;
    @Autowired WidgetActions actions;
    @Autowired WidgetProposalProjection projection;
    @Autowired AssistantService assistant;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ru.taskflow.telegram.application.WidgetReceiptProjection receipts;
    UUID owner;
    @BeforeEach void clean() { jdbc.execute("TRUNCATE users CASCADE");owner=users.findOrCreateByTelegram(12345,"widget_test","Тест",null).id(); }

    @Test void preferenceDefaultsToCardsAndSparseUpdatesPreservePlainMode() {
        assertThat(users.getSettings(owner).telegramWidgetsEnabled()).isTrue();
        users.updateSettings(owner,new UpdateSettingsRequest(null,null,null,null,null,null,null,null,null,null,null,null,null,null,false));
        users.updateSettings(owner,new UpdateSettingsRequest(null,null,null,null,30,null,null,null,null,null,null,null));
        assertThat(users.getSettings(owner).telegramWidgetsEnabled()).isFalse();
    }
    @Test void foreignOwnerAndWrongMessageCannotChangeTask() {
        var context=reminder();UUID stranger=users.findOrCreateByTelegram(9999,"other","Другой",null).id();
        assertThatThrownBy(()->actions.act(stranger,12345,100,key(context,"done"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->actions.act(owner,12345,101,key(context,"done"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->actions.act(owner,9999,100,key(context,"done"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.findById(owner,context.taskId()).status()).isEqualTo(TaskStatus.TODO);
    }
    @Test void doneSurvivesSourceNotificationRemovalAndReplayDoesNotRepeat() {
        var context=reminder();var result=actions.act(owner,12345,100,key(context,"done"));
        assertThat(result.terminal()).isTrue();
        assertThat(tasks.findById(owner,context.taskId()).status()).isEqualTo(TaskStatus.DONE);
        assertThat(actions.act(owner,12345,100,key(context,"later"))).isEqualTo(result);
        assertThat(syncCount(context)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT task_id IS NULL FROM scheduled_notifications WHERE payload_type='WIDGET_SYNC'",Boolean.class)).isTrue();
    }
    @Test void concurrentSnoozeOnlyCreatesOneReminderAndNeverMovesDeadline() throws Exception {
        var context=reminder();var before=tasks.findById(owner,context.taskId()).deadline();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->actions.act(owner,12345,100,key(context,"later")));
            var second=pool.submit(()->actions.act(owner,12345,100,key(context,"later")));
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
        }
        assertThat(tasks.findById(owner,context.taskId()).deadline().isEqual(before)).isTrue();
        assertThat(tasks.getReminders(owner,context.taskId())).hasSize(1);
        assertThat(tasks.getReminders(owner,context.taskId()).getFirst().fireAt())
                .isBetween(OffsetDateTime.now().plusMinutes(59),OffsetDateTime.now().plusMinutes(61));
        assertThat(syncCount(context)).isEqualTo(1);
    }
    @Test void movedDeadlineAndCancelledReminderInvalidateOldCard() {
        var context=reminder();
        tasks.update(owner,context.taskId(),new UpdateTaskRequest(null,null,null,null,OffsetDateTime.now().plusDays(5),null,null,null,null));
        assertThat(actions.act(owner,12345,100,key(context,"done")).heading()).isEqualTo("Задача уже изменилась");
        assertThat(tasks.findById(owner,context.taskId()).status()).isEqualTo(TaskStatus.TODO);
        var other=reminder();
        var reminderId=jdbc.queryForObject("SELECT reminder_id FROM scheduled_notifications WHERE id=?",UUID.class,other.referenceId());
        tasks.cancelReminder(owner,other.taskId(),reminderId);
        assertThat(actions.act(owner,12345,100,key(other,"done")).terminal()).isTrue();
        assertThat(tasks.findById(owner,other.taskId()).status()).isEqualTo(TaskStatus.TODO);
    }
    @Test void staleRevisionReturnsCurrentCardWithoutDoingAnotherAction() {
        var context=reminder();var updated=actions.act(owner,12345,100,key(context,"details"));
        var stale=actions.act(owner,12345,100,key(context,"done"));
        assertThat(stale).isEqualTo(updated);assertThat(stale.terminal()).isFalse();
        assertThat(tasks.findById(owner,context.taskId()).status()).isEqualTo(TaskStatus.TODO);
    }
    @Test void proposalPageAndSelectionApplyExactlyOneTaskWithIndependentReminder() {
        UUID proposal=proposal(2);var context=proposalCard(proposal);
        var page=actions.act(owner,12345,100,key(context,"pg1"));
        assertThat(page.page()).isEqualTo(1);
        var selected=actions.act(owner,12345,100,page.key()+":sel0");
        assertThat(selected.selected()).isEqualTo(1);
        var result=actions.act(owner,12345,100,selected.key()+":a");
        assertThat(result.terminal()).isTrue();assertThat(result.status()).isEqualTo("Сохранено: 1 из 1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
        var taskId=jdbc.queryForObject("SELECT id FROM tasks WHERE user_id=?",UUID.class,owner);
        var task=tasks.findById(owner,taskId);
        assertThat(task.deadline().toInstant().getEpochSecond()-tasks.getReminders(owner,taskId).getFirst().fireAt().toInstant().getEpochSecond()).isEqualTo(4800);
        assertThat(actions.act(owner,12345,100,selected.key()+":a")).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class)).isEqualTo(1);
    }
    @Test void proposalChangedInAppRequiresReviewBeforeApplying() {
        UUID proposal=proposal(1);var context=proposalCard(proposal);
        jdbc.update("UPDATE assistant_proposal_actions SET payload=jsonb_set(payload,'{title}','\"Другое дело\"'::jsonb) WHERE proposal_id=?",proposal);
        var refreshed=actions.act(owner,12345,100,key(context,"a"));
        assertThat(refreshed.title()).isEqualTo("Другое дело");assertThat(refreshed.terminal()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class)).isZero();
        actions.act(owner,12345,100,refreshed.key()+":a");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class)).isEqualTo(1);
    }
    @Test void applyGuardRejectsConcurrentProposalEdits() {
        UUID id=proposal(1);var expected=assistant.findById(owner,id).actions();
        jdbc.update("UPDATE assistant_proposal_actions SET accepted=false WHERE proposal_id=?",id);
        assertThatThrownBy(()->assistant.apply(owner,id,expected)).hasMessageContaining("Разбор изменился");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class)).isZero();
    }
    @Test void savingCardAndEnqueuingSyncRollBackTogether() {
        var context=reminder();var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{
            store.save(context,context.state().finish("Тест","Результат"),0,false);throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(context.id(),owner).orElseThrow().revision()).isZero();assertThat(syncCount(context)).isZero();
    }
    @Test void persistentDeliveredReminderCanBeSnoozedAfterChainAdvancement() {
        var context=reminder();
        jdbc.update("UPDATE tasks SET persistent_reminder=true WHERE id=?",context.taskId());
        jdbc.update("UPDATE reminders SET status='DISMISSED',chain_step=0 WHERE id=(SELECT reminder_id FROM scheduled_notifications WHERE id=?)",context.referenceId());
        tasks.scheduleReminder(owner,context.taskId(),OffsetDateTime.now().plusMinutes(15));
        var result=actions.act(owner,12345,100,key(context,"later"));
        assertThat(result.terminal()).isTrue();
        assertThat(tasks.getReminders(owner,context.taskId())).hasSize(1);
        assertThat(tasks.getReminders(owner,context.taskId()).getFirst().fireAt()).isAfter(OffsetDateTime.now().plusMinutes(59));
    }

    @Test void disabledTelegramDoesNotTurnCheckinRejectionIntoSuccess() {
        var reminder=reminder();UUID prompt=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO engagement_prompts(id,user_id,task_id,kind,deadline_snapshot,expires_at,sent_at,created_at)
                VALUES (?,?,?,'TASK_CHECKIN',?,?,NOW(),NOW())
                """,prompt,owner,reminder.taskId(),reminder.deadline(),OffsetDateTime.now().plusDays(1));
        var c=store.reserve(owner,"ENGAGEMENT",prompt,reminder.taskId(),reminder.deadline(),12345,new WidgetState("checkin","Как успехи?",List.of()));
        store.bind(c.id(),100,"WIDGET");c=store.find(c.id(),owner).orElseThrow();var context=c;
        users.updateSettings(owner,new UpdateSettingsRequest(null,false,null,null,null,null,null,null,null,null,null,null));
        assertThatThrownBy(()->actions.act(owner,12345,100,key(context,"later"))).hasMessageContaining("выключены");
        assertThat(store.find(c.id(),owner).orElseThrow().state().terminal()).isFalse();
        assertThat(jdbc.queryForObject("SELECT answered_at IS NULL FROM engagement_prompts WHERE id=?",Boolean.class,prompt)).isTrue();
    }
    @Test void expiredRequestCannotStartActionAndWorkDayIsVisible() {
        var c=reminder();
        assertThatThrownBy(()->actions.act(owner,12345,100,key(c,"done"),java.time.Instant.now().minusSeconds(1)))
                .hasMessageContaining("Время ответа истекло");
        assertThat(tasks.findById(owner,c.taskId()).status()).isEqualTo(TaskStatus.TODO);
        UUID id=proposal(1);
        jdbc.update("UPDATE assistant_proposal_actions SET payload=jsonb_set(payload,'{planned_date}',to_jsonb(?::text)) WHERE proposal_id=?",
                OffsetDateTime.now().plusDays(1).toString(),id);
        var p=proposalCard(id);
        assertThat(p.state().rows()).hasSize(3);assertThat(p.state().rows().getFirst().getFirst()).isEqualTo("День работы");
        assertThat(WidgetTemplate.caption(p.state())).contains("День работы:");
    }

    @Test void partialApplyShowsActualCountAndNeverReportsFullSuccess() {
        UUID id=proposal(2);
        jdbc.update("UPDATE assistant_proposal_actions SET type='COMPLETE',target_task_id=?,payload='{}' WHERE proposal_id=? AND ordinal=1",UUID.randomUUID(),id);
        var c=proposalCard(id);var result=actions.act(owner,12345,100,key(c,"a"));
        assertThat(result.terminal()).isTrue();assertThat(result.heading()).isEqualTo("Сохранено частично");
        assertThat(result.status()).isEqualTo("Сохранено: 1 из 2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class)).isEqualTo(1);
        assertThat(actions.act(owner,12345,100,key(c,"a"))).isEqualTo(result);
    }

    @Test void receiptUndoIsBoundToOwnerAndReplayedCreationNeverRunsAgain() {
        UUID id=proposal(1);
        var applied=assistant.resolveAutomatically(owner,id);
        assertThat(applied.applied().appliedCount()).isEqualTo(1);
        var receipt=receiptCard(id);
        assertThat(receipt.state().undoAvailable()).isTrue();
        UUID stranger=users.findOrCreateByTelegram(8888,"other_receipt","Другой",null).id();
        assertThatThrownBy(()->actions.act(stranger,12345,100,key(receipt,"undo"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->actions.act(owner,12345,101,key(receipt,"undo"))).isInstanceOf(IllegalArgumentException.class);
        var undone=actions.act(owner,12345,100,key(receipt,"undo"));
        assertThat(undone.heading()).isEqualTo("Действие отменено");
        assertThat(undone.undoAvailable()).isFalse();
        assertThat(undone.taskId()).isNull();
        assertThat(actions.act(owner,12345,100,key(receipt,"undo"))).isEqualTo(undone);
        assertThat(assistant.resolveAutomatically(owner,id).applied().outcomes().getFirst().undone()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE NOT is_deleted",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class)).isEqualTo(1);
    }

    @Test void receiptPagingUndoesOnlyTheSelectedCreation() {
        UUID id=proposal(2);assistant.resolveAutomatically(owner,id);
        var receipt=receiptCard(id);
        var second=actions.act(owner,12345,100,key(receipt,"pg1"));
        assertThat(second.title()).isEqualTo("Провести пару 1");
        actions.act(owner,12345,100,second.key()+":undo");
        assertThat(jdbc.queryForList("SELECT title FROM tasks WHERE NOT is_deleted",String.class)).containsExactly("Провести пару 0");
    }

    @Test void receiptUndoRejectsLaterEditsWithoutReplacingCurrentData() {
        UUID id=proposal(1);var result=assistant.resolveAutomatically(owner,id);
        var receipt=receiptCard(id);UUID task=result.applied().outcomes().getFirst().taskId();
        tasks.update(owner,task,new UpdateTaskRequest("Уже исправлено",null,null,null,null,null,null,null,null));
        assertThatThrownBy(()->actions.act(owner,12345,100,key(receipt,"undo"))).hasMessageContaining("уже изменилась");
        assertThat(tasks.findById(owner,task).title()).isEqualTo("Уже исправлено");
        assertThat(store.find(receipt.id(),owner).orElseThrow().revision()).isZero();
    }

    private WidgetContext receiptCard(UUID proposal) {
        var context=store.reserve(owner,"RECEIPT",proposal,null,null,12345,receipts.render(owner,proposal,0));
        store.bind(context.id(),100,"WIDGET");return store.find(context.id(),owner).orElseThrow();
    }

    private WidgetContext reminder() {
        var deadline=OffsetDateTime.now().plusDays(2);
        var task=tasks.createQuick(owner,new CreateTaskRequest("Проверить карточку",null,TaskPriority.MEDIUM,deadline,null,null,List.of(),30,TaskSource.MANUAL));
        UUID notification=jdbc.queryForObject("SELECT id FROM scheduled_notifications WHERE task_id=? AND channel='TELEGRAM' ORDER BY fire_at LIMIT 1",UUID.class,task.id());
        var context=store.reserve(owner,"REMINDER",notification,task.id(),deadline,12345,new WidgetState("reminder",task.title(),List.of()));
        store.bind(context.id(),100,"WIDGET");return store.find(context.id(),owner).orElseThrow();
    }
    private UUID proposal(int count) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO assistant_proposals(id,short_code,user_id,source_channel,input_kind,status,expires_at) VALUES (?,?,?,'TELEGRAM','VOICE','PENDING',?)",
                id,id.toString().substring(0,8),owner,OffsetDateTime.now().plusHours(24));
        var deadline=OffsetDateTime.now().plusDays(1).withNano(0);var reminder=deadline.minusMinutes(80);
        for(int i=0;i<count;i++) jdbc.update("""
                INSERT INTO assistant_proposal_actions(id,proposal_id,ordinal,type,payload,summary,accepted)
                VALUES (?,?,?,'CREATE',?::jsonb,'Создать задачу',true)
                """,UUID.randomUUID(),id,i,"{\"title\":\"Провести пару "+i+"\",\"deadline\":\""+deadline+"\",\"reminder_at\":\""+reminder+"\"}");
        return id;
    }
    private WidgetContext proposalCard(UUID proposal) {
        var p=assistant.findById(owner,proposal);var context=store.reserve(owner,"PROPOSAL",proposal,null,null,12345,projection.render(owner,p,0));
        store.bind(context.id(),100,"WIDGET");return store.find(context.id(),owner).orElseThrow();
    }
    private String key(WidgetContext context,String action) { return context.state().key()+":"+action; }
    private int syncCount(WidgetContext context) { return jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE payload_type='WIDGET_SYNC' AND widget_context_id=?",Integer.class,context.id()); }
}
