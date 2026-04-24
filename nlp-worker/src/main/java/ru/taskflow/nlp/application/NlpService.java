package ru.taskflow.nlp.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.taskflow.nlp.domain.LlmProvider;
import ru.taskflow.nlp.domain.ParsedTasks;
import ru.taskflow.nlp.domain.SpeechToTextProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class NlpService {

    private final LlmProvider llmProvider;
    private final SpeechToTextProvider speechToTextProvider;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "nlp:cache:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    public ParsedTasks parseText(String text, String userTimezone, String userLanguage) {
        String cacheKey = getCacheKey(text);

        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, ParsedTasks.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached tasks", e);
            }
        }

        ParsedTasks result = llmProvider.parseTasksFromText(text, userTimezone, userLanguage);

        try {
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache parsed tasks", e);
        }

        return result;
    }

    public ParsedTasks parseVoice(byte[] audioBytes, String userTimezone, String userLanguage) {
        String transcript = speechToTextProvider.transcribeAudio(audioBytes);

        if (transcript.isEmpty()) {
            return new ParsedTasks(java.util.List.of());
        }

        return parseText(transcript, userTimezone, userLanguage);
    }

    private String getCacheKey(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return CACHE_PREFIX + sb.toString();
        } catch (Exception e) {
            log.warn("Failed to compute cache key", e);
            return CACHE_PREFIX + System.nanoTime();
        }
    }
}
