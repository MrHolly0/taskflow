package ru.taskflow.nlp.infrastructure;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.taskflow.nlp.api.NlpParseResult;
import ru.taskflow.nlp.api.NlpParsedTask;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NlpWorkerClient {

    private final NlpGatewayConfig config;
    private final RestClient restClient;

    @CircuitBreaker(name = "nlp-worker", fallbackMethod = "parseTextFallback")
    @Retry(name = "nlp-worker")
    public NlpParseResult parseText(String text, String userTimezone, String userLanguage) {
        var request = Map.of(
            "text", text,
            "userTimezone", userTimezone,
            "userLanguage", userLanguage
        );

        try {
            var response = restClient.post()
                .uri(config.getWorkerUrl() + "/nlp/parse-text")
                .body(request)
                .retrieve()
                .body(NlpWorkerResponse.class);

            if (response != null && response.tasks != null) {
                return new NlpParseResult(response.tasks);
            }
        } catch (Exception e) {
            log.error("Failed to call nlp-worker parseText", e);
            throw e;
        }

        return new NlpParseResult(List.of());
    }

    @CircuitBreaker(name = "nlp-worker", fallbackMethod = "parseVoiceFallback")
    @Retry(name = "nlp-worker")
    public NlpParseResult parseVoice(byte[] audioBytes, String userTimezone, String userLanguage) {
        try {
            var response = restClient.post()
                .uri(config.getWorkerUrl() + "/nlp/parse-voice?userTimezone=" + userTimezone + "&userLanguage=" + userLanguage)
                .header("Content-Type", "application/octet-stream")
                .body(audioBytes)
                .retrieve()
                .body(NlpWorkerResponse.class);

            if (response != null && response.tasks != null) {
                return new NlpParseResult(response.tasks);
            }
        } catch (Exception e) {
            log.error("Failed to call nlp-worker parseVoice", e);
            throw e;
        }

        return new NlpParseResult(List.of());
    }

    public NlpParseResult parseTextFallback(String text, String userTimezone, String userLanguage, Exception e) {
        log.warn("NLP parseText circuit breaker fallback, returning empty result", e);
        return new NlpParseResult(List.of());
    }

    public NlpParseResult parseVoiceFallback(byte[] audioBytes, String userTimezone, String userLanguage, Exception e) {
        log.warn("NLP parseVoice circuit breaker fallback, returning empty result", e);
        return new NlpParseResult(List.of());
    }

    record NlpWorkerResponse(List<NlpParsedTask> tasks) {
    }
}
