package ru.taskflow.nlp.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.taskflow.nlp.domain.SpeechToTextProvider;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Кэш по хэшу аудио, а не текста: расшифровка — чистая функция от байтов,
 * в отличие от разбора текста (см. NlpService.parseText), для которого
 * состояние задач меняет корректную интерпретацию.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptionService {

    private static final String CACHE_PREFIX = "nlp:transcript:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);

    private final SpeechToTextProvider speechToTextProvider;
    private final StringRedisTemplate redis;
    private final AudioNormalizer normalizer;
    private final ru.taskflow.shared.resources.ResourcePolicy policy;

    public String transcribe(byte[] audioBytes) {
        String cacheKey = cacheKey(audioBytes);

        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null && cached.length()<=policy.getMaxTextChars()) {
            return cached;
        }

        var audio=normalizer.normalize(audioBytes);
        var context=ru.taskflow.shared.resources.ModelRequestContext.required();
        String transcript;
        try(var scope=ru.taskflow.shared.resources.ModelRequestContext.open(
                new ru.taskflow.shared.resources.ModelRequestContext(context.userId(),context.deadlineMillis(),audio.seconds()))) {
            transcript=speechToTextProvider.transcribeAudio(audio.wav());
        }
        if(transcript.length()>policy.getMaxTextChars())
            throw new ru.taskflow.shared.resources.ResourceRejectedException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Расшифровка слишком длинная");

        try {
            if(!transcript.isBlank()) redis.opsForValue().set(cacheKey, transcript, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Не удалось закэшировать расшифровку", e);
        }

        return transcript;
    }

    private String cacheKey(byte[] audioBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(audioBytes);
            StringBuilder sb = new StringBuilder(CACHE_PREFIX);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
