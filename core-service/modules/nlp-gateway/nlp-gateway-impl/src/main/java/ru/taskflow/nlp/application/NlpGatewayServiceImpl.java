package ru.taskflow.nlp.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.nlp.api.NlpParseResult;
import ru.taskflow.nlp.infrastructure.NlpWorkerClient;

/**
 * Шлюз к сервису обработки естественного языка.
 *
 * Предоставляет методы для разбора текста и голоса через отдельный NLP worker,
 * с обработкой ошибок и fallback на пустой результат.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NlpGatewayServiceImpl implements NlpGatewayService {

    private final NlpWorkerClient nlpWorkerClient;

    @Override
    public NlpParseResult parseText(String text, String userTimezone, java.util.List<String> existingGroups) {
        try {
            return nlpWorkerClient.parseText(text, userTimezone, "ru", existingGroups);
        } catch (ru.taskflow.shared.resources.ResourceRejectedException e) { throw e;
        } catch (Exception e) {
            log.error("NLP parseText failed", e);
            return new NlpParseResult(java.util.List.of());
        }
    }

    @Override
    public NlpParseResult parseVoice(byte[] audioBytes, String userTimezone, java.util.List<String> existingGroups) {
        try {
            return nlpWorkerClient.parseVoice(audioBytes, userTimezone, "ru", existingGroups);
        } catch (ru.taskflow.shared.resources.ResourceRejectedException e) { throw e;
        } catch (Exception e) {
            log.error("NLP parseVoice failed", e);
            return new NlpParseResult(java.util.List.of());
        }
    }

    @Override
    public LlmToolResponse callWithTools(LlmToolRequest request) {
        try {
            return nlpWorkerClient.callWithTools(request);
        } catch (ru.taskflow.shared.resources.ResourceRejectedException e) { throw e;
        } catch (Exception e) {
            log.error("NLP callWithTools failed", e);
            return LlmToolResponse.unavailable();
        }
    }

    @Override
    public String transcribe(byte[] audioBytes) {
        try {
            return nlpWorkerClient.transcribe(audioBytes);
        } catch (ru.taskflow.shared.resources.ResourceRejectedException e) { throw e;
        } catch (Exception e) {
            log.error("NLP transcribe failed", e);
            return null;
        }
    }
}
