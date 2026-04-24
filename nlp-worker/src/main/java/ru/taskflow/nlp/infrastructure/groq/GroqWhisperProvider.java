package ru.taskflow.nlp.infrastructure.groq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ru.taskflow.nlp.domain.SpeechToTextProvider;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroqWhisperProvider implements SpeechToTextProvider {

    private final GroqConfig config;
    private final RestClient restClient;

    @Override
    public String transcribeAudio(byte[] audioBytes) {
        try {
            var body = new LinkedMultiValueMap<String, Object>();
            body.add("file", new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return "audio.ogg";
                }
            });
            body.add("model", config.getWhisperModel());
            body.add("language", "ru");
            body.add("response_format", "text");

            var response = restClient.post()
                .uri("/audio/transcriptions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .body(body)
                .retrieve()
                .body(String.class);

            return response != null ? response.trim() : "";
        } catch (Exception e) {
            log.error("Failed to transcribe audio via Groq Whisper", e);
            return "";
        }
    }
}
