package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.user.api.UserService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class FocusHintIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TaskService taskService;
    @Autowired
    private UserService userService;
    @Autowired
    private AuditService auditService;
    @MockBean
    private NlpGatewayService nlpGatewayService;

    @Test
    void firstStep_isGeneratedOnceAndStartAfterShowIsRecorded() {
        when(nlpGatewayService.callWithTools(any())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_first_step",
                        "{\"first_step\":\"Открой список исходных данных\"}")),
                null, 102, 12, false));
        UUID userId = newUser();
        UUID taskId = taskService.createQuick(userId,
                new CreateTaskRequest("подготовить отчёт по проекту", null, null, null,
                        null, null, List.of(), null, null)).id();

        assertThat(taskService.getFocusHint(userId, taskId).hint())
                .isEqualTo("Открой список исходных данных");
        assertThat(taskService.getFocusHint(userId, taskId).hint())
                .isEqualTo("Открой список исходных данных");
        verify(nlpGatewayService, times(1)).callWithTools(any());

        taskService.update(userId, taskId,
                new UpdateTaskRequest(null, null, null, TaskStatus.IN_PROGRESS,
                        null, null, null, null, null));

        assertThat(auditService.getHistory(taskId, userId))
                .extracting(event -> event.eventType())
                .contains(AuditEventType.FOCUS_HINT_SHOWN.name(), AuditEventType.STARTED_AFTER_HINT.name());
    }

    @Test
    void shortTaskWithoutLongEstimate_doesNotCallModel() {
        UUID userId = newUser();
        UUID taskId = taskService.createQuick(userId,
                new CreateTaskRequest("вынести мусор", null, null, null,
                        null, null, List.of(), 30, null)).id();

        assertThat(taskService.getFocusHint(userId, taskId).hint()).isNull();
        verify(nlpGatewayService, times(0)).callWithTools(any());
    }

    private UUID newUser() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return userService.findOrCreateByTelegram(
                telegramId, "focus_hint_" + telegramId, "Test", "User").id();
    }
}
