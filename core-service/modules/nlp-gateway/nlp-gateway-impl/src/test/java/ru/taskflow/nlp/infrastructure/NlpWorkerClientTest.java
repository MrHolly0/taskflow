package ru.taskflow.nlp.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;
import ru.taskflow.nlp.api.LlmMessage;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class NlpWorkerClientTest {

    private static final String WORKER_URL = "http://localhost:8081";
    private static final String TOOL_CALL_URL = WORKER_URL + "/nlp/tool-call";

    private MockRestServiceServer server;
    private NlpWorkerClient client;
    private ObjectMapper objectMapper;
    private JsonNode capturedBody;

    @BeforeEach
    void setUp() {
        NlpGatewayConfig config = new NlpGatewayConfig();
        config.setWorkerUrl(WORKER_URL);

        objectMapper = new ObjectMapper();

        RestClient.Builder restClientBuilder = RestClient.builder();
        RestClient restClient = restClientBuilder.build();

        // callWithTools ходит через отдельный toolCallRestClient (R4) — мок-сервер
        // биндим именно к нему, restClient тут не участвует в вызовах инструментов.
        RestClient.Builder toolCallBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(toolCallBuilder).build();
        RestClient toolCallRestClient = toolCallBuilder.build();

        client = new NlpWorkerClient(config, restClient, toolCallRestClient);
    }

    @Test
    void callWithTools_returnsParsedResponse() {
        server.expect(requestTo(TOOL_CALL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "toolCalls": [
                            {"id": "call_1", "name": "create_task", "argumentsJson": "{\\"title\\":\\"купить молоко\\"}"}
                          ],
                          "text": null,
                          "inputTokens": 100,
                          "outputTokens": 20
                        }
                        """, MediaType.APPLICATION_JSON));

        LlmToolResponse response = client.callWithTools(new LlmToolRequest(
                List.of(LlmMessage.system("prompt"), LlmMessage.user("купи молоко")),
                List.of(Map.of("type", "function"))
        ));

        assertThat(response.failed()).isFalse();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().id()).isEqualTo("call_1");
        assertThat(response.toolCalls().getFirst().name()).isEqualTo("create_task");
        assertThat(response.toolCalls().getFirst().argumentsJson()).isEqualTo("{\"title\":\"купить молоко\"}");
        assertThat(response.inputTokens()).isEqualTo(100);
        assertThat(response.outputTokens()).isEqualTo(20);

        server.verify();
    }

    @Test
    void callWithTools_throwsOnServiceUnavailableWithoutProxy() {
        server.expect(requestTo(TOOL_CALL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.callWithTools(new LlmToolRequest(
                List.of(LlmMessage.user("купи молоко")), List.of())))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);

        server.verify();
    }

    @Test
    void callWithTools_serviceUnavailableTriggersFallback() {
        server.expect(requestTo(TOOL_CALL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                        CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class))
                .withPropertyValues("resilience4j.retry.instances.nlp-worker-tools.max-attempts=1")
                .withBean(NlpWorkerClient.class, () -> client)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NlpWorkerClient proxiedClient = context.getBean(NlpWorkerClient.class);
                    assertThat(AopUtils.isAopProxy(proxiedClient)).isTrue();

                    LlmToolResponse response = proxiedClient.callWithTools(new LlmToolRequest(
                            List.of(LlmMessage.user("купи молоко")), List.of()));

                    assertThat(response.failed()).isTrue();
                    assertThat(response).isEqualTo(LlmToolResponse.unavailable());
                    server.verify();
                });
    }

    @Test
    void callWithTools_fallbackMarksFailed() {
        LlmToolResponse response = client.callWithToolsFallback(
                new LlmToolRequest(List.of(LlmMessage.user("привет")), List.of()),
                new RuntimeException("circuit open")
        );

        assertThat(response.failed()).isTrue();
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.text()).isNull();
        assertThat(response.inputTokens()).isZero();
        assertThat(response.outputTokens()).isZero();
    }

    @Test
    void callWithTools_propagatesMessagesAndTools() {
        server.expect(requestTo(TOOL_CALL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    MockClientHttpRequest mockRequest = (MockClientHttpRequest) request;
                    capturedBody = objectMapper.readTree(mockRequest.getBodyAsString());
                })
                .andRespond(withSuccess("""
                        {"toolCalls": [], "text": "ok", "inputTokens": 0, "outputTokens": 0}
                        """, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> tools = List.of(Map.of("type", "function", "function", Map.of("name", "create_task")));

        client.callWithTools(new LlmToolRequest(
                List.of(
                        LlmMessage.system("systemPrompt"),
                        LlmMessage.user("купи молоко"),
                        LlmMessage.toolResult("call_1", "create_task", "{\"status\":\"ok\"}")
                ),
                tools
        ));

        server.verify();

        JsonNode messages = capturedBody.get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("systemPrompt");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("купи молоко");
        assertThat(messages.get(2).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(2).get("toolCallId").asText()).isEqualTo("call_1");
        assertThat(messages.get(2).get("content").asText()).isEqualTo("{\"status\":\"ok\"}");

        JsonNode capturedTools = capturedBody.get("tools");
        assertThat(capturedTools).isNotNull();
        assertThat(capturedTools.isArray()).isTrue();
        assertThat(capturedTools).hasSize(1);
        assertThat(capturedTools.get(0).get("function").get("name").asText()).isEqualTo("create_task");
    }

    /**
     * Ассистентское сообщение с вызовами инструментов кодируется в content
     * под маркером, который распознаёт nlp-worker (отдельный деплоймент,
     * общего кода нет). Тест независимо повторяет его decode-логику и
     * проверяет побайтовую совместимость, включая случай, когда JSON
     * аргументов содержит те же символы, что использует сама кодировка
     * как разделители, — base64-слой обязан сделать это безопасным.
     */
    @Test
    void assistantToolCalls_roundTripsThroughWorkerDecoding() {
        List<LlmToolCall> calls = List.of(
                new LlmToolCall("call_1", "create_task",
                        "{\"title\":\"молоко и хлеб\",\"note\":\"~|##tool_calls##\"}"),
                new LlmToolCall("call_2", "complete_task", "{\"task_ref\":\"T1\"}")
        );

        LlmMessage message = LlmMessage.assistantToolCalls(calls);

        assertThat(message.role()).isEqualTo("assistant");
        assertThat(message.toolCallId()).isNull();
        assertThat(message.name()).isNull();

        List<LlmToolCall> decoded = decodeToolCallsLikeWorker(message.content());

        assertThat(decoded).containsExactlyElementsOf(calls);
    }

    // Дублирует ToolCallMessage.isAssistantToolCalls()/decodeToolCalls() из
    // nlp-worker (ru.taskflow.nlp.domain.ToolCallMessage) побайтово.
    private static final String TOOL_CALLS_MARKER = "##tool_calls##";
    private static final String CALL_SEPARATOR = "|";
    private static final String FIELD_SEPARATOR = "~";

    private static boolean isAssistantToolCallsLikeWorker(String content) {
        return content != null && content.startsWith(TOOL_CALLS_MARKER);
    }

    private static List<LlmToolCall> decodeToolCallsLikeWorker(String content) {
        if (!isAssistantToolCallsLikeWorker(content)) {
            return List.of();
        }
        String payload = content.substring(TOOL_CALLS_MARKER.length());
        if (payload.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(payload.split("\\" + CALL_SEPARATOR))
                .map(NlpWorkerClientTest::decodeCallLikeWorker)
                .toList();
    }

    private static LlmToolCall decodeCallLikeWorker(String encoded) {
        String[] fields = encoded.split(FIELD_SEPARATOR, -1);
        return new LlmToolCall(decodeField(fields[0]), decodeField(fields[1]), decodeField(fields[2]));
    }

    private static String decodeField(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    @Test void jsonPlanningOptionsAndRawTextSurviveTheWorkerTransport() {
        server.expect(requestTo(TOOL_CALL_URL)).andExpect(method(HttpMethod.POST)).andExpect(request -> {
            capturedBody = objectMapper.readTree(((MockClientHttpRequest)request).getBodyAsString());
        }).andRespond(withSuccess("{\"toolCalls\":[],\"text\":\"{\\\"clarification\\\":\\\"Какую задачу?\\\"}\",\"inputTokens\":100,\"outputTokens\":20}",MediaType.APPLICATION_JSON));
        var response = client.callWithTools(new LlmToolRequest(List.of(LlmMessage.user("Проверить договор")),List.of(),"low",null,true));
        server.verify();
        assertThat(capturedBody.path("jsonOutput").asBoolean()).isTrue();
        assertThat(capturedBody.path("reasoningEffort").asText()).isEqualTo("low");
        assertThat(response.failed()).isFalse();assertThat(response.toolCalls()).isEmpty();
        assertThat(response.text()).isEqualTo("{\"clarification\":\"Какую задачу?\"}");
        assertThat(response.inputTokens()).isEqualTo(100);assertThat(response.outputTokens()).isEqualTo(20);
    }

}
