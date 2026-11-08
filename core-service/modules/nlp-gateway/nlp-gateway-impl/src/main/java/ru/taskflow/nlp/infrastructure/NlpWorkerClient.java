package ru.taskflow.nlp.infrastructure;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpParseResult;
import ru.taskflow.nlp.api.NlpParsedTask;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NlpWorkerClient {

    private final NlpGatewayConfig config;
    private final RestClient restClient;
    private final RestClient toolCallRestClient;

    @CircuitBreaker(name = "nlp-worker", fallbackMethod = "parseTextFallback")
    @Retry(name = "nlp-worker")
    public NlpParseResult parseText(String text, String userTimezone, String userLanguage, List<String> existingGroups) {
        var request = new java.util.HashMap<String, Object>();
        request.put("text", text);
        request.put("userTimezone", userTimezone);
        request.put("userLanguage", userLanguage);
        request.put("existingGroups", existingGroups != null ? existingGroups : List.of());

        try {
            var response = restClient.post()
                .uri(config.getWorkerUrl() + "/nlp/parse-text")
                .body(request)
                .retrieve()
                .body(NlpWorkerResponse.class);

            if (response != null && response.tasks != null) {
                return new NlpParseResult(response.tasks);
            }
        } catch (ru.taskflow.shared.resources.ResourceRejectedException e) { throw e;
        } catch (Exception e) {
            log.error("Failed to call nlp-worker parseText", e);
            throw e;
        }

        return new NlpParseResult(List.of());
    }

    @CircuitBreaker(name = "nlp-worker", fallbackMethod = "parseVoiceFallback")
    @Retry(name = "nlp-worker")
    public NlpParseResult parseVoice(byte[] audioBytes, String userTimezone, String userLanguage, List<String> existingGroups) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return "voice.ogg";
                }
            });
            body.add("userTimezone", userTimezone);
            body.add("userLanguage", userLanguage);
            if (existingGroups != null) {
                existingGroups.forEach(g -> body.add("existingGroups", g));
            }

            var response = restClient.post()
                .uri(config.getWorkerUrl() + "/nlp/parse-voice")
                .body(body)
                .retrieve()
                .body(NlpWorkerResponse.class);

            if (response != null && response.tasks != null) {
                return new NlpParseResult(response.tasks);
            }
        } catch (ru.taskflow.shared.resources.ResourceRejectedException e) { throw e;
        } catch (Exception e) {
            log.error("Failed to call nlp-worker parseVoice", e);
            throw e;
        }

        return new NlpParseResult(List.of());
    }

    public NlpParseResult parseTextFallback(String text, String userTimezone, String userLanguage, List<String> existingGroups, Exception e) {
        if (e instanceof ru.taskflow.shared.resources.ResourceRejectedException rejected) throw rejected;
        log.warn("NLP parseText circuit breaker fallback, returning empty result", e);
        return new NlpParseResult(List.of());
    }

    public NlpParseResult parseVoiceFallback(byte[] audioBytes, String userTimezone, String userLanguage, List<String> existingGroups, Exception e) {
        if (e instanceof ru.taskflow.shared.resources.ResourceRejectedException rejected) throw rejected;
        log.warn("NLP parseVoice circuit breaker fallback, returning empty result", e);
        return new NlpParseResult(List.of());
    }

    @CircuitBreaker(name = "nlp-worker-tools", fallbackMethod = "callWithToolsFallback")
    @Retry(name = "nlp-worker-tools")
    public LlmToolResponse callWithTools(LlmToolRequest request) {
        try {
            var response = toolCallRestClient.post()
                .uri(config.getWorkerUrl() + "/nlp/tool-call")
                .body(request)
                .retrieve()
                .body(ToolCallWireResponse.class);

            if (response != null) {
                return new LlmToolResponse(
                    response.toolCalls != null ? response.toolCalls : List.of(),
                    response.text,
                    response.inputTokens,
                    response.outputTokens,
                    false
                );
            }
        } catch (ru.taskflow.shared.resources.ResourceRejectedException e) { throw e;
        } catch (Exception e) {
            log.error("Failed to call nlp-worker callWithTools", e);
            throw e;
        }

        return new LlmToolResponse(List.of(), null, 0, 0, false);
    }

    public LlmToolResponse callWithToolsFallback(LlmToolRequest request, Exception e) {
        if (e instanceof ru.taskflow.shared.resources.ResourceRejectedException rejected) throw rejected;
        log.warn("NLP callWithTools circuit breaker fallback, marking failed", e);
        return LlmToolResponse.unavailable();
    }

    @CircuitBreaker(name = "nlp-worker", fallbackMethod = "transcribeFallback")
    @Retry(name = "nlp-worker")
    public String transcribe(byte[] audioBytes) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return "voice.ogg";
                }
            });

            var response = restClient.post()
                .uri(config.getWorkerUrl() + "/nlp/transcribe")
                .body(body)
                .retrieve()
                .body(TranscribeWireResponse.class);

            return response != null ? response.text() : null;
        } catch (ru.taskflow.shared.resources.ResourceRejectedException e) { throw e;
        } catch (Exception e) {
            log.error("Failed to call nlp-worker transcribe", e);
            throw e;
        }
    }

    public String transcribeFallback(byte[] audioBytes, Exception e) {
        if (e instanceof ru.taskflow.shared.resources.ResourceRejectedException rejected) throw rejected;
        log.warn("NLP transcribe circuit breaker fallback, returning null", e);
        return null;
    }

    record NlpWorkerResponse(List<NlpParsedTask> tasks) {
    }

    record ToolCallWireResponse(List<LlmToolCall> toolCalls, String text, int inputTokens, int outputTokens) {
    }

    record TranscribeWireResponse(String text) {
    }
}
