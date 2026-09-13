package ru.taskflow.nlp.infrastructure.groq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import ru.taskflow.nlp.domain.RawToolCall;
import ru.taskflow.nlp.domain.ToolCallMessage;
import ru.taskflow.nlp.domain.ToolCallProvider;
import ru.taskflow.nlp.domain.ToolCallRequest;
import ru.taskflow.nlp.domain.ToolCallResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Вызов модели с инструментами (function calling) по OpenAI-совместимому
 * диалогу API. Ничего не интерпретирует — отдаёт вызовы инструментов как
 * есть, разбор и валидация остаются на стороне ядра. От конкретного
 * поставщика (Groq, ProxyAPI, ...) зависит только имя, адрес, ключ и модель —
 * всё приходит из конфигурации, экземпляр строится на каждую запись
 * app.llm.providers отдельно (см. ToolCallProviderConfiguration).
 * <p>
 * В отличие от прежней версии не глотает исключения сама — их ловит
 * {@link FallbackToolCallProvider}, которому нужно отличить настоящий отказ
 * провайдера от пустого, но легитимного ответа модели (пустой toolCalls с
 * непустым text — это «модель ничего не вызвала, а написала»,
 * не неисправность).
 */
@Slf4j
public class OpenAiCompatibleToolCallProvider implements ToolCallProvider {

    private final String name;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleToolCallProvider(String name, String apiKey, String model,
                                             ObjectMapper objectMapper, RestClient restClient) {
        this.name = name;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ToolCallResult call(ToolCallRequest req) {
        try {
            return callApi(req);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Провайдер " + name + " вернул неразбираемый ответ", e);
        }
    }

    private ToolCallResult callApi(ToolCallRequest req) throws JsonProcessingException {
        var request = new HashMap<String, Object>();
        request.put("model", model);
        request.put("messages", req.messages().stream().map(this::toApiMessage).toList());
        request.put("tools", req.tools());
        request.put("tool_choice", "auto");
        request.put("temperature", 0.1);

        String rawBody = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(String.class);

        if (rawBody == null) {
            throw new IllegalStateException("Провайдер " + name + " вернул пустое тело ответа");
        }

        return parseResponse(rawBody);
    }

    private ToolCallResult parseResponse(String rawBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(rawBody);
        JsonNode choices = root != null ? root.get("choices") : null;
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("Ответ поставщика не содержит choices");
        }

        JsonNode message = choices.get(0).get("message");
        if (message == null || !message.isObject()) {
            throw new IllegalStateException("Ответ поставщика не содержит message");
        }

        List<RawToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray()) {
            for (JsonNode callNode : toolCallsNode) {
                JsonNode function = callNode.get("function");
                toolCalls.add(new RawToolCall(
                        callNode.path("id").asText(null),
                        function != null ? function.path("name").asText(null) : null,
                        function != null ? function.path("arguments").asText(null) : null
                ));
            }
        }

        String text = message.hasNonNull("content") ? message.get("content").asText() : null;

        JsonNode usage = root.get("usage");
        int inputTokens = usage != null ? usage.path("prompt_tokens").asInt(0) : 0;
        int outputTokens = usage != null ? usage.path("completion_tokens").asInt(0) : 0;

        return new ToolCallResult(toolCalls, text, inputTokens, outputTokens);
    }

    private Map<String, Object> toApiMessage(ToolCallMessage message) {
        Map<String, Object> apiMessage = new LinkedHashMap<>();
        apiMessage.put("role", message.role());

        if (message.isAssistantToolCalls()) {
            apiMessage.put("content", null);
            apiMessage.put("tool_calls", message.decodeToolCalls().stream()
                    .map(this::toApiToolCall)
                    .toList());
            return apiMessage;
        }

        apiMessage.put("content", message.content());
        if (message.toolCallId() != null) {
            apiMessage.put("tool_call_id", message.toolCallId());
        }
        if (message.name() != null) {
            apiMessage.put("name", message.name());
        }
        return apiMessage;
    }

    private Map<String, Object> toApiToolCall(RawToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        function.put("arguments", call.argumentsJson());

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", call.id());
        toolCall.put("type", "function");
        toolCall.put("function", function);
        return toolCall;
    }
}
