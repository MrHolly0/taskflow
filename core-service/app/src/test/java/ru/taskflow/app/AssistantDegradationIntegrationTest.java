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
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.user.api.UserService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Деградация целиком на настоящей базе — два разных исхода, которые
 * различаются только по данным (ProposalStatus и расход токенов), не по коду
 * вызывающей стороны. Б6 задания "прокрутка повторов, сквозные сценарии".
 * Отдельный коммит от Б5 по прямому указанию задания.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AssistantDegradationIntegrationTest {
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
        return userService.findOrCreateByTelegram(telegramId, "degrade_it_" + telegramId, "Test", "User").id();
    }

    @Test
    void modelUnavailable_savesRawTextAsTaskWithDegradedSource() {
        var userId = newUser();
        when(nlpGatewayService.callWithTools(any())).thenReturn(LlmToolResponse.unavailable());

        var proposal = assistantService.handleText(userId, "купить молоко и хлеб", AssistantChannel.WEB);

        assertThat(proposal.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(proposal.inputTokens()).isZero();
        assertThat(proposal.outputTokens()).isZero();

        var tasks = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20))
                .getContent();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().title()).isEqualTo("купить молоко и хлеб");
        assertThat(tasks.getFirst().source()).isEqualTo(TaskSource.ASSISTANT_WEB_DEGRADED);
    }

    @Test
    void modelRespondedWithNothing_doesNotCreateAnyTask() {
        var userId = newUser();
        // Модель ответила (расход не нулевой, failed=false), но ни действий, ни
        // текста — реплика без содержания, а не сбой обращения.
        when(nlpGatewayService.callWithTools(any()))
                .thenReturn(new LlmToolResponse(List.of(), null, 900, 4, false));

        var proposal = assistantService.handleText(userId, "привет", AssistantChannel.WEB);

        assertThat(proposal.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(proposal.inputTokens()).isEqualTo(900);
        assertThat(proposal.outputTokens()).isEqualTo(4);

        var tasks = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20))
                .getContent();
        assertThat(tasks).isEmpty();
    }
}
