package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.user.api.UserService;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Предложение ассистента целиком: подменённая модель (NlpGatewayService —
 * реального обращения к Groq здесь нет) до применения подтверждённых
 * действий и сохранённых задач. Именно такого сценария не хватало, когда
 * chk_action_type дожил до живого прогона — юнит-тесты подменяют репозиторий,
 * а этот идёт по настоящей базе целиком. Б5 задания "прокрутка повторов,
 * сквозные сценарии".
 *
 * Контур ассистента (промпт, ToolRegistry, AgentLoop, AssistantServiceImpl) не
 * тронут — подменена только точка выхода к внешней модели.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AssistantProposalIntegrationTest {
    @MockBean
    private ru.taskflow.assistant.application.AssistantRateLimiter admission;

    @org.junit.jupiter.api.BeforeEach
    void allowTestRequests() {
        org.mockito.Mockito.when(admission.allow(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }


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

    @MockBean
    private NlpGatewayService nlpGatewayService;

    private UUID newUser() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return userService.findOrCreateByTelegram(telegramId, "proposal_it_" + telegramId, "Test", "User").id();
    }

    @Test
    void proposal_appliesOnlyConfirmedActions_withCorrectSourceAndNoTraceOfRejected() {
        var userId = newUser();
        var toolCall = new LlmToolCall("call-1", "propose_actions", """
                {"actions":[
                  {"type":"create","title":"Подтверждённая задача"},
                  {"type":"create","title":"Отклонённая задача"}
                ]}
                """);
        when(nlpGatewayService.callWithTools(any()))
                .thenReturn(new LlmToolResponse(List.of(toolCall), null, 400, 60, false));

        var proposal = assistantService.handleText(userId,
                "запиши две вещи: подтверждённая задача и отклонённая задача", AssistantChannel.WEB);

        assertThat(proposal.status()).isEqualTo(ProposalStatus.PENDING);
        assertThat(proposal.actions()).hasSize(2);
        // оба действия по умолчанию подтверждены (не двоякость) — снимаем второе
        assistantService.setActionAccepted(userId, proposal.id(), 2, false);

        var result = assistantService.apply(userId, proposal.id());

        assertThat(result.status()).isEqualTo(ProposalStatus.APPLIED);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.totalCount()).isEqualTo(1);

        var tasks = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20))
                .getContent();
        assertThat(tasks).hasSize(1);
        var createdTask = tasks.getFirst();
        assertThat(createdTask.title()).isEqualTo("Подтверждённая задача");
        assertThat(createdTask.source()).isEqualTo(TaskSource.ASSISTANT_WEB);
        assertThat(tasks).noneMatch(t -> t.title().equals("Отклонённая задача"));
    }

    /**
     * После отката f207832 модель день исполнения не предлагает — это
     * правка человека в карточке подтверждения, тот же путь, что и у
     * напоминания (updateActionPlannedDate, не контур). Юнит-тестов для
     * этого класса дефектов уже было мало один раз: с chk_action_type
     * подмена репозитория в юнит-тесте не поймала падение вставки, поймал
     * только прогон на настоящем Postgres — здесь то же самое.
     */
    @Test
    void apply_carriesManuallyEditedPlannedDateToCreatedTask() {
        var userId = newUser();
        var toolCall = new LlmToolCall("call-1", "propose_actions", """
                {"actions":[{"type":"create","title":"Сходить к врачу"}]}
                """);
        when(nlpGatewayService.callWithTools(any()))
                .thenReturn(new LlmToolResponse(List.of(toolCall), null, 400, 60, false));

        var proposal = assistantService.handleText(userId, "сходить к врачу", AssistantChannel.WEB);
        var plannedDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0);
        assistantService.updateActionPlannedDate(userId, proposal.id(), 1, plannedDate);

        var result = assistantService.apply(userId, proposal.id());
        assertThat(result.status()).isEqualTo(ProposalStatus.APPLIED);

        var tasks = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20))
                .getContent();
        assertThat(tasks).hasSize(1);
        var createdTask = tasks.getFirst();
        assertThat(createdTask.plannedDate()).isEqualTo(plannedDate);
        assertThat(createdTask.plannedDateSetAt()).isNotNull();
    }

    @Test
    void apply_withNoPlannedDateNeeded_leavesPlannedDateEmpty() {
        var userId = newUser();
        var toolCall = new LlmToolCall("call-1", "propose_actions", """
                {"actions":[{"type":"create","title":"Разобрать почту"}]}
                """);
        when(nlpGatewayService.callWithTools(any()))
                .thenReturn(new LlmToolResponse(List.of(toolCall), null, 400, 60, false));

        var proposal = assistantService.handleText(userId, "разобрать почту", AssistantChannel.WEB);
        assistantService.updateActionPlannedDate(userId, proposal.id(), 1, null);

        var result = assistantService.apply(userId, proposal.id());
        assertThat(result.status()).isEqualTo(ProposalStatus.APPLIED);

        var createdTask = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20))
                .getContent().getFirst();
        assertThat(createdTask.plannedDate()).isNull();
        assertThat(createdTask.plannedDateSetAt()).isNull();
    }
}
