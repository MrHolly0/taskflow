package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.user.api.UserService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class FocusPlannedDateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AssistantService assistantService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private UserService userService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private NlpGatewayService nlpGatewayService;
    @MockBean
    private ru.taskflow.assistant.application.AssistantRateLimiter limiter;

    @org.junit.jupiter.api.BeforeEach
    void allowRequests() {
        when(limiter.allow(any())).thenReturn(true);
    }

    @Test
    void lazyRequest_setsPlannedDateFromOriginalMessage_andSecondRequestDoesNotCallAgain() {
        LocalDate suggestedDate = LocalDate.now(ZoneId.of("Europe/Moscow"));
        when(nlpGatewayService.callWithTools(any()))
                .thenReturn(new LlmToolResponse(
                        List.of(new LlmToolCall("call-create", "propose_actions", """
                                {"actions":[{"type":"create","title":"подготовить пакет документов"}]}
                                """)), null, 300, 30, false))
                .thenReturn(new LlmToolResponse(
                        List.of(new LlmToolCall("call-date", "suggest_planned_date",
                                "{\"planned_date\":\"" + suggestedDate + "\"}")),
                        null, 120, 12, false));
        UUID userId = newUser();
        String sourceText = "на этой неделе надо подготовить пакет документов";
        var proposal = assistantService.handleText(userId, sourceText, AssistantChannel.WEB);
        UUID taskId = assistantService.apply(userId, proposal.id()).outcomes().getFirst().taskId();

        var firstFocus = taskService.getFocusTasks(userId, null);

        assertThat(firstFocus.tasks()).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo(taskId);
            assertThat(task.deadline()).isNull();
            assertThat(task.plannedDate()).isNull();
        });
        verify(nlpGatewayService, times(1)).callWithTools(any());

        var suggestion = taskService.suggestPlannedDate(userId, taskId);

        assertThat(suggestion.plannedDate()).isNotNull();
        assertThat(suggestion.plannedDate().toLocalDate()).isEqualTo(suggestedDate);
        assertThat(taskService.findById(userId, taskId).plannedDateSetAt()).isNotNull();

        taskService.suggestPlannedDate(userId, taskId);
        verify(nlpGatewayService, times(2)).callWithTools(any());

        var captor = forClass(LlmToolRequest.class);
        verify(nlpGatewayService, times(2)).callWithTools(captor.capture());
        String narrowCallInput = captor.getAllValues().get(1).messages().stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst().orElseThrow().content();
        assertThat(narrowCallInput).contains(sourceText);
    }

    @Test
    void failedLazyRequest_savesAttemptAndIsNotRepeated() {
        doThrow(new RuntimeException("модель недоступна"))
                .when(nlpGatewayService).callWithTools(any());
        UUID userId = newUser();
        UUID taskId = taskService.createQuick(userId,
                new CreateTaskRequest("подготовить пакет документов", null, null, null,
                        null, null, List.of(), null, null)).id();

        assertThat(taskService.suggestPlannedDate(userId, taskId).plannedDate()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT planned_date_suggestion_attempted_at IS NOT NULL FROM tasks WHERE id = ?",
                Boolean.class, taskId)).isTrue();

        assertThat(taskService.suggestPlannedDate(userId, taskId).plannedDate()).isNull();
        verify(nlpGatewayService, times(1)).callWithTools(any());
    }

    private UUID newUser() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return userService.findOrCreateByTelegram(
                telegramId, "focus_date_" + telegramId, "Test", "User").id();
    }
}
