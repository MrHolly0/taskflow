package ru.taskflow.nlp.infrastructure.groq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.taskflow.nlp.domain.RawToolCall;
import ru.taskflow.nlp.domain.ToolCallMessage;
import ru.taskflow.nlp.domain.ToolCallRequest;
import ru.taskflow.nlp.domain.ToolCallResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleToolCallProviderTest {

    private static final String BASE_URL = "https://api.groq.com/openai/v1";
    private static final String COMPLETIONS_URL = BASE_URL + "/chat/completions";
    private static final String OK_RESPONSE = """
            {"choices": [{"message": {"content": "ok"}}]}
            """;

    private MockRestServiceServer server;
    private OpenAiCompatibleToolCallProvider provider;
    private ObjectMapper objectMapper;
    private JsonNode capturedBody;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        provider = new OpenAiCompatibleToolCallProvider("groq", "test-key", "llama-3.3-70b-versatile",
                objectMapper, restClient);
    }

    @Test
    void call_parsesToolCalls() {
        server.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": null,
                                "tool_calls": [
                                  {"id": "call_1", "type": "function", "function": {"name": "create_task", "arguments": "{\\"title\\":\\"купить молоко\\"}"}},
                                  {"id": "call_2", "type": "function", "function": {"name": "complete_task", "arguments": "{\\"task_ref\\":\\"T1\\"}"}}
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ToolCallResult result = provider.call(new ToolCallRequest(
                List.of(ToolCallMessage.system("prompt"), ToolCallMessage.user("купи молоко и закрой T1")),
                List.of(Map.of("type", "function"))
        ));

        assertThat(result.toolCalls()).hasSize(2);

        RawToolCall first = result.toolCalls().get(0);
        assertThat(first.id()).isEqualTo("call_1");
        assertThat(first.name()).isEqualTo("create_task");
        assertThat(first.argumentsJson()).isEqualTo("{\"title\":\"купить молоко\"}");

        RawToolCall second = result.toolCalls().get(1);
        assertThat(second.id()).isEqualTo("call_2");
        assertThat(second.name()).isEqualTo("complete_task");
        assertThat(second.argumentsJson()).isEqualTo("{\"task_ref\":\"T1\"}");

        server.verify();
    }

    @Test
    void call_parsesPlainText() {
        server.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "уточните, пожалуйста, какую задачу закрыть"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ToolCallResult result = provider.call(new ToolCallRequest(
                List.of(ToolCallMessage.system("prompt"), ToolCallMessage.user("закрой это")),
                List.of()
        ));

        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.text()).isEqualTo("уточните, пожалуйста, какую задачу закрыть");

        server.verify();
    }

    @Test
    void call_throwsOnMalformedBody() {
        // Кидает, а не глотает: FallbackToolCallProvider должен отличить настоящий
        // отказ провайдера от легитимного пустого ответа модели, а для этого нужен
        // сигнал исключением, не безмолвный ToolCallResult.empty().
        server.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("это не json совсем", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> provider.call(new ToolCallRequest(
                List.of(ToolCallMessage.user("привет")),
                List.of()
        ))).isInstanceOf(IllegalStateException.class);

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"choices\":[]}", "{\"choices\":null}", "{\"choices\":{}}",
            "{\"choices\":[{}]}", "{\"choices\":[{\"message\":null}]}",
            "{\"choices\":[{\"message\":\"text\"}]}", "null"
    })
    void call_rejectsIncompleteResponse(String body) {
        server.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.call(new ToolCallRequest(
                List.of(ToolCallMessage.user("привет")), List.of())))
                .isInstanceOf(IllegalStateException.class);

        server.verify();
    }

    @Test
    void call_acceptsEmptyMessage() {
        server.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{}}]}", MediaType.APPLICATION_JSON));

        ToolCallResult result = provider.call(new ToolCallRequest(
                List.of(ToolCallMessage.user("привет")), List.of()));

        assertThat(result).isEqualTo(ToolCallResult.empty());
        server.verify();
    }

    @Test
    void name_returnsConfiguredName() {
        assertThat(provider.name()).isEqualTo("groq");
    }

    @Test
    void call_sendsToolsAndMessagesWithoutResponseFormat() {
        expectAndCapture();

        List<Map<String, Object>> tools = List.of(Map.of("type", "function", "function", Map.of("name", "create_task")));

        provider.call(new ToolCallRequest(
                List.of(
                        ToolCallMessage.system("systemPrompt"),
                        ToolCallMessage.user("купи молоко"),
                        ToolCallMessage.toolResult("call_1", "create_task", "{\"status\":\"ok\"}")
                ),
                tools
        ));

        server.verify();

        assertThat(capturedBody.has("response_format"))
                .as("response_format несовместим с tools и не должен ставиться")
                .isFalse();

        JsonNode messages = capturedBody.get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("systemPrompt");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("купи молоко");
        assertThat(messages.get(2).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(2).get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(messages.get(2).get("content").asText()).isEqualTo("{\"status\":\"ok\"}");

        JsonNode capturedTools = capturedBody.get("tools");
        assertThat(capturedTools).isNotNull();
        assertThat(capturedTools.isArray()).isTrue();
        assertThat(capturedTools).hasSize(1);
        assertThat(capturedTools.get(0).get("function").get("name").asText()).isEqualTo("create_task");
    }

    @Test
    void call_serializesAssistantToolCallsAsArray() {
        expectAndCapture();

        List<RawToolCall> calls = List.of(new RawToolCall("call_1", "create_task", "{\"title\":\"купить молоко\"}"));

        provider.call(new ToolCallRequest(
                List.of(
                        ToolCallMessage.system("systemPrompt"),
                        ToolCallMessage.user("купи молоко"),
                        ToolCallMessage.assistantToolCalls(calls),
                        ToolCallMessage.toolResult("call_1", "create_task", "{\"status\":\"ok\"}")
                ),
                List.of()
        ));

        server.verify();

        JsonNode assistantMessage = capturedBody.get("messages").get(2);

        assertThat(assistantMessage.get("role").asText()).isEqualTo("assistant");
        assertThat(assistantMessage.has("content") && !assistantMessage.get("content").isNull())
                .as("вызовы должны уйти в tool_calls, а не текстом в content")
                .isFalse();

        JsonNode toolCalls = assistantMessage.get("tool_calls");
        assertThat(toolCalls).isNotNull();
        assertThat(toolCalls.isArray()).isTrue();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0).get("id").asText()).isEqualTo("call_1");
        assertThat(toolCalls.get(0).get("type").asText()).isEqualTo("function");
        assertThat(toolCalls.get(0).get("function").get("name").asText()).isEqualTo("create_task");
        assertThat(toolCalls.get(0).get("function").get("arguments").asText())
                .isEqualTo("{\"title\":\"купить молоко\"}");
    }

    @Test
    void call_readsTokenUsage() {
        server.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "choices": [{"message": {"content": "ok"}}],
                          "usage": {"prompt_tokens": 123, "completion_tokens": 45}
                        }
                        """, MediaType.APPLICATION_JSON));

        ToolCallResult result = provider.call(new ToolCallRequest(List.of(ToolCallMessage.user("привет")), List.of()));

        assertThat(result.inputTokens()).isEqualTo(123);
        assertThat(result.outputTokens()).isEqualTo(45);

        server.verify();
    }

    private void expectAndCapture() {
        server.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    MockClientHttpRequest mockRequest = (MockClientHttpRequest) request;
                    capturedBody = objectMapper.readTree(mockRequest.getBodyAsString());
                })
                .andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));
    }
    @ParameterizedTest
    @ValueSource(strings = {"openai/gpt-oss-120b", "llama-3.3-70b-versatile"})
    void forwardsOptionalReasoningEffortOnlyToSupportedModels(String model) throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAiCompatibleToolCallProvider("groq", "test-key", model, objectMapper, builder.build());
        expectAndCapture();
        var request = objectMapper.readValue(
                "{\"messages\":[],\"tools\":[],\"reasoningEffort\":\"low\"}", ToolCallRequest.class);
        provider.call(request);
        server.verify();
        if (model.startsWith("openai/gpt-oss-"))
            assertThat(capturedBody.path("reasoning_effort").asText()).isEqualTo("low");
        else assertThat(capturedBody.has("reasoning_effort")).isFalse();
    }

    @Test void jsonPlansDoNotSendFunctionCallingParameters() {
        expectAndCapture();
        provider.call(new ToolCallRequest(List.of(ToolCallMessage.user("Верни JSON")), List.of(), null, null, true));
        server.verify();
        assertThat(capturedBody.has("tools")).isFalse();
        assertThat(capturedBody.has("tool_choice")).isFalse();
        assertThat(capturedBody.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test void forwardsDeclaredRequiredTool() {
        expectAndCapture();
        var tools = List.<Map<String,Object>>of(Map.of("type", "function", "function", Map.of("name", "execute_code")));
        provider.call(new ToolCallRequest(List.of(ToolCallMessage.user("Купить блокнот")), tools, null, "execute_code"));
        server.verify();
        assertThat(capturedBody.path("tool_choice").path("function").path("name").asText()).isEqualTo("execute_code");
    }

}
