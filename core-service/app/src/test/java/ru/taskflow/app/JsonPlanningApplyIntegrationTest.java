package ru.taskflow.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.*;
import ru.taskflow.assistant.application.AssistantRateLimiter;
import ru.taskflow.nlp.api.*;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.telegram.bot-token=", "app.public-base-url="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JsonPlanningApplyIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired AssistantService assistant;
    @Autowired TaskService tasks;
    @Autowired UserService users;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean NlpGatewayService model;
    @MockBean AssistantRateLimiter limiter;
    @BeforeEach void allow() { when(limiter.allow(any())).thenReturn(true); }
    UUID user() { return users.findOrCreateByTelegram(ThreadLocalRandom.current().nextLong(1000000000L,9000000000L),"json_apply","Test","User").id(); }
    TaskResponse task(UUID user, OffsetDateTime deadline) {
        return tasks.create(user,new CreateTaskRequest("Проверить договор аренды",null,TaskPriority.MEDIUM,deadline,null,null,List.of(),null,TaskSource.MANUAL));
    }
    void reply(String plan) { when(model.callWithTools(any())).thenReturn(new LlmToolResponse(List.of(),plan,20,30,false)); }

    @Test void preparationKeepsItsPlannedDayWithoutInventingADeadline() {
        UUID user = user();
        reply("""
                {"steps":[{"action":{"type":"create","title":"Доделать доклад","planned_date":"2030-11-16T09:00:00+03:00"}},
                {"action":{"type":"create","title":"Сдать доклад","deadline":"2030-11-17T18:00:00+03:00"}}]}
                """);
        var proposal = assistant.handleText(user, "16 ноября 2030 доделать доклад, а 17 ноября в 18:00 сдать", AssistantChannel.WEB);
        assertThat(proposal.actions()).hasSize(2);
        var applied = assistant.apply(user, proposal.id());
        assertThat(applied.appliedCount()).isEqualTo(2);
        var preparation = tasks.findById(user, applied.outcomes().getFirst().taskId());
        var delivery = tasks.findById(user, applied.outcomes().getLast().taskId());
        assertThat(preparation.deadline()).isNull();
        assertThat(preparation.plannedDate().toInstant()).isEqualTo(OffsetDateTime.parse("2030-11-16T09:00:00+03:00").toInstant());
        assertThat(delivery.deadline().toInstant()).isEqualTo(OffsetDateTime.parse("2030-11-17T18:00:00+03:00").toInstant());
        assertThat(assistant.apply(user, proposal.id())).isEqualTo(applied);
    }

    @Test void relativeReminderIsStoredAtTheActualDeadlineOffsetAndApplyIsIdempotent() {
        UUID user = user();var deadline = OffsetDateTime.now().plusDays(8).withNano(0);var target = task(user,deadline);
        var before = tasks.getReminders(user,target.id());
        reply("{\"steps\":[{\"select\":{\"query\":\"аренды\"},\"action\":{\"type\":\"remind\",\"reminder_before_deadline_minutes\":35}}]}");
        var proposal = assistant.handleText(user,"Напомни о договоре аренды за 35 минут до срока",AssistantChannel.WEB);
        assertThat(proposal.actions()).hasSize(1);assertThat(tasks.getReminders(user,target.id())).isEqualTo(before);
        var applied = assistant.apply(user,proposal.id());assertThat(applied.appliedCount()).isEqualTo(1);
        assertThat(assistant.apply(user,proposal.id())).isEqualTo(applied);
        assertThat(tasks.getReminders(user,target.id())).hasSize(before.size()+1).filteredOn(r -> r.fireAt().isEqual(deadline.minusMinutes(35))).hasSize(1);
        assertThat(tasks.findById(user,target.id()).deadline().toInstant()).isEqualTo(deadline.toInstant());
    }

    @Test void creatingWithRelativeReminderPersistsBothDeadlineAndNotification() {
        UUID user = user();var deadline = OffsetDateTime.now().plusDays(8).withNano(0);
        reply("{\"steps\":[{\"action\":{\"type\":\"create\",\"title\":\"Посетить выставку\",\"deadline\":\"" + deadline + "\",\"reminder_before_deadline_minutes\":60}}]}");
        var proposal = assistant.handleText(user,"Посетить выставку, напомни за час",AssistantChannel.WEB);
        assertThat(proposal.actions()).hasSize(1);
        var applied = assistant.apply(user,proposal.id());assertThat(applied.appliedCount()).isEqualTo(1);
        UUID task = applied.outcomes().getFirst().taskId();
        assertThat(tasks.findById(user,task).deadline().toInstant()).isEqualTo(deadline.toInstant());
        assertThat(tasks.getReminders(user,task)).singleElement().satisfies(r -> assertThat(r.fireAt().toInstant()).isEqualTo(deadline.minusHours(1).toInstant()));
    }

    @Test void rescheduleAndRelativeReminderAreBothAppliedInsteadOfDroppingTheReminder() {
        UUID user = user();var target = task(user,OffsetDateTime.now().plusDays(2));
        var deadline = OffsetDateTime.now().plusDays(8).withNano(0);
        reply("{\"steps\":[{\"select\":{\"query\":\"аренды\"},\"action\":{\"type\":\"reschedule\",\"new_deadline\":\"" + deadline + "\",\"reminder_before_deadline_minutes\":45}}]}");
        var proposal = assistant.handleText(user,"Перенеси срок договора аренды, напомни за 45 минут",AssistantChannel.WEB);
        assertThat(proposal.actions()).extracting(a -> a.type()).containsExactly(AssistantActionType.RESCHEDULE,AssistantActionType.REMIND);
        assertThat(assistant.apply(user,proposal.id()).appliedCount()).isEqualTo(2);
        assertThat(tasks.findById(user,target.id()).deadline().toInstant()).isEqualTo(deadline.toInstant());
        assertThat(tasks.getReminders(user,target.id())).filteredOn(r -> r.fireAt().isEqual(deadline.minusMinutes(45))).hasSize(1);
    }
    @Test void acceptingAnotherAlternativeDisablesTheFirstEvenThroughTheCheckboxEndpoint() {
        UUID user = user();var target = task(user,null);
        reply("{\"alternatives\":[{\"select\":{\"query\":\"аренды\"},\"action\":{\"type\":\"complete\"}},{\"action\":{\"type\":\"create\",\"title\":\"Закрыть договор\"}}]}");
        var proposal = assistant.handleText(user,"закрыть договор",AssistantChannel.WEB);
        assertThat(proposal.exclusive()).isTrue();
        var changed = assistant.setActionAccepted(user,proposal.id(),2,true);
        assertThat(changed.actions().stream().filter(a -> a.accepted()).count()).isEqualTo(1);
        assertThat(changed.actions().getLast().accepted()).isTrue();
        assertThat(assistant.apply(user,proposal.id()).appliedCount()).isEqualTo(1);
        assertThat(tasks.findById(user,target.id()).status()).isEqualTo(TaskStatus.TODO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isEqualTo(2);
    }

    @Test void anInvalidExclusiveProposalCannotApplyBothActions() {
        UUID user = user();var target = task(user,null);
        reply("{\"alternatives\":[{\"select\":{\"query\":\"аренды\"},\"action\":{\"type\":\"complete\"}},{\"action\":{\"type\":\"create\",\"title\":\"Закрыть договор\"}}]}");
        var proposal = assistant.handleText(user,"закрыть договор",AssistantChannel.WEB);
        jdbc.update("UPDATE assistant_proposal_actions SET accepted=true WHERE proposal_id=?",proposal.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assistant.apply(user,proposal.id()))
                .isInstanceOf(ru.taskflow.shared.exception.ValidationException.class);
        assertThat(tasks.findById(user,target.id()).status()).isEqualTo(TaskStatus.TODO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }

    @Test void reminderBeforeRescheduleUsesFinalDeadlineAndSurvivesApplyingTheMove() {
        UUID owner=user();var target=task(owner,OffsetDateTime.now().plusDays(30));
        var deadline=OffsetDateTime.now().plusDays(4).withNano(0);
        reply("{\"steps\":[{\"select\":{\"query\":\"аренды\"},\"action\":{\"type\":\"remind\",\"reminder_before_deadline_minutes\":75}},{\"select\":{\"query\":\"аренды\"},\"action\":{\"type\":\"reschedule\",\"new_deadline\":\""+deadline+"\"}}]}");
        var proposal=assistant.handleText(owner,"Напомни за 75 минут до срока договора аренды и перенеси его срок",AssistantChannel.WEB);
        assertThat(proposal.actions()).extracting(a -> a.type()).containsExactly(AssistantActionType.RESCHEDULE,AssistantActionType.REMIND);
        assertThat(assistant.apply(owner,proposal.id()).appliedCount()).isEqualTo(2);
        assertThat(tasks.findById(owner,target.id()).deadline().toInstant()).isEqualTo(deadline.toInstant());
        assertThat(tasks.getReminders(owner,target.id())).filteredOn(r -> r.fireAt().toInstant().equals(deadline.minusMinutes(75).toInstant())).hasSize(1);
    }

    @Test void conflictingTerminalAndScheduleOperationsAskWithoutWriting() {
        UUID owner=user();var target=task(owner,OffsetDateTime.now().plusDays(30));
        var before=tasks.getReminders(owner,target.id());
        reply("{\"steps\":[{\"select\":{\"query\":\"аренды\"},\"action\":{\"type\":\"complete\"}},{\"select\":{\"query\":\"аренды\"},\"action\":{\"type\":\"remind\",\"reminder_before_deadline_minutes\":75}}]}");
        var proposal=assistant.handleText(owner,"Заверши договор аренды и напомни за 75 минут",AssistantChannel.WEB);
        assertThat(proposal.actions()).isEmpty();assertThat(proposal.clarification()).isNotBlank();
        assertThat(tasks.getReminders(owner,target.id())).isEqualTo(before);
        assertThat(tasks.findById(owner,target.id()).status()).isEqualTo(TaskStatus.TODO);
    }

    @Test void repeatedQuickTextRequestUsesOneProposalAndCreatesOneTask() {
        UUID owner=user();String text="Добавь купить лампу";
        reply("{\"steps\":[{\"action\":{\"type\":\"create\",\"title\":\"Купить лампу\"}}]}");
        var first=assistant.handleText(owner,text,AssistantChannel.WEB,AssistantEntryPoint.QUICK_ADD,"quick-text-once");
        var repeated=assistant.handleText(owner,text,AssistantChannel.WEB,AssistantEntryPoint.QUICK_ADD,"quick-text-once");
        assertThat(repeated.id()).isEqualTo(first.id());
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).callWithTools(any());
        var applied=assistant.apply(owner,first.id());assertThat(applied.appliedCount()).isEqualTo(1);
        assertThat(assistant.apply(owner,repeated.id())).isEqualTo(applied);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
    }

    @Test void repeatedVoiceUploadTranscribesOnceAndKeepsTheSameProposal() {
        UUID owner=user();byte[] audio={1,2,3,4};
        when(model.transcribe(org.mockito.ArgumentMatchers.any(byte[].class))).thenReturn("Добавь купить лампу");
        reply("{\"steps\":[{\"action\":{\"type\":\"create\",\"title\":\"Купить лампу\"}}]}");
        var first=assistant.handleVoice(owner,audio,AssistantChannel.WEB,AssistantEntryPoint.QUICK_ADD,"voice-once");
        var repeated=assistant.handleVoice(owner,audio,AssistantChannel.WEB,AssistantEntryPoint.QUICK_ADD,"voice-once");
        assertThat(repeated.id()).isEqualTo(first.id());
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).transcribe(org.mockito.ArgumentMatchers.any(byte[].class));
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).callWithTools(any());
        assertThat(assistant.apply(owner,first.id()).appliedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
    }

    @Test void quickAddKeepsTheNewTaskFirstAndDoesNotApplyBothInterpretations() {
        UUID owner=user();var existing=tasks.create(owner,new CreateTaskRequest("Проверить почту",null,TaskPriority.MEDIUM,null,null,null,List.of(),null,TaskSource.MANUAL));
        reply("{\"steps\":[{\"select\":{\"query\":\"почту\"},\"action\":{\"type\":\"complete\"}}]}");
        var proposal=assistant.handleText(owner,"почта",AssistantChannel.WEB,AssistantEntryPoint.QUICK_ADD);
        assertThat(proposal.exclusive()).isTrue();
        assertThat(proposal.actions()).extracting(a -> a.type()).containsExactly(AssistantActionType.CREATE,AssistantActionType.COMPLETE);
        assertThat(proposal.actions().stream().filter(a -> a.accepted()).count()).isEqualTo(1);
        assertThat(assistant.apply(owner,proposal.id()).appliedCount()).isEqualTo(1);
        assertThat(tasks.findById(owner,existing.id()).status()).isEqualTo(TaskStatus.TODO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,owner)).isEqualTo(2);
    }


    @Autowired ru.taskflow.assistant.application.AssistantRequestStore requestStore;

    @Test void concurrentVoiceRetryAndDifferentPayloadDoNotRepeatTranscription() throws Exception {
        UUID user=user();String key="voice-race-"+UUID.randomUUID();byte[] audio={1,2,3};
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(model.transcribe(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{
            entered.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test timeout");
            return "Купить чай";
        });
        reply("{\"steps\":[{\"action\":{\"type\":\"create\",\"title\":\"Купить чай\"}}]}");
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->assistant.handleVoice(user,audio,AssistantChannel.WEB,AssistantEntryPoint.CHAT,key));
            try {
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                org.assertj.core.api.Assertions.assertThatThrownBy(()->assistant.handleVoice(user,audio,AssistantChannel.WEB,AssistantEntryPoint.CHAT,key))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                e->assertThat(e.getStatusCode().value()).isEqualTo(409));
                org.assertj.core.api.Assertions.assertThatThrownBy(()->assistant.handleVoice(user,new byte[]{4},AssistantChannel.WEB,AssistantEntryPoint.CHAT,key))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                e->assertThat(e.getStatusCode().value()).isEqualTo(409));
            } finally {release.countDown();}
            var result=first.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(assistant.handleVoice(user,audio,AssistantChannel.WEB,AssistantEntryPoint.CHAT,key).id()).isEqualTo(result.id());
        }
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).transcribe(audio);
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).callWithTools(org.mockito.ArgumentMatchers.any());
    }

    @Test void expiredClaimCanBeRecoveredButOldOwnerCannotPersist() {
        UUID user=user();String key="claim-"+UUID.randomUUID();String hash="f".repeat(64);
        var first=requestStore.claim(user,key,hash);
        jdbc.update("UPDATE assistant_request_results SET lease_until=NOW()-INTERVAL '1 second' WHERE user_id=? AND request_key=?",user,key);
        var second=requestStore.claim(user,key,hash);assertThat(second.owner()).isNotEqualTo(first.owner());
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->requestStore.complete(user,key,hash,first.owner(),()->{called.set(true);return null;}))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(called).isFalse();
        requestStore.abandon(user,key,first.owner());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->requestStore.claim(user,key,hash))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        requestStore.abandon(user,key,second.owner());assertThat(requestStore.claim(user,key,hash).owner()).isNotNull();
    }
}
