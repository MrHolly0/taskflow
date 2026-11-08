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
        "app.telegram.bot-token=", "app.public-base-url=", "app.assistant.planning-mode=BATCH_PLAN"})
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
    @MockBean NlpGatewayService model;
    @MockBean AssistantRateLimiter limiter;
    @BeforeEach void allow() { when(limiter.allow(any())).thenReturn(true); }
    UUID user() { return users.findOrCreateByTelegram(ThreadLocalRandom.current().nextLong(1000000000L,9000000000L),"json_apply","Test","User").id(); }
    TaskResponse task(UUID user, OffsetDateTime deadline) {
        return tasks.create(user,new CreateTaskRequest("Проверить договор аренды",null,TaskPriority.MEDIUM,deadline,null,null,List.of(),null,TaskSource.MANUAL));
    }
    void reply(String plan) { when(model.callWithTools(any())).thenReturn(new LlmToolResponse(List.of(),plan,20,30,false)); }

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
}
