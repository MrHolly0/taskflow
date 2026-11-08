package ru.taskflow.nlp.infrastructure.groq;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.groq")
@Data
public class GroqConfig {
    private String apiKey;
    private String baseUrl;
    private String llmModel;
    private String whisperModel = "whisper-large-v3";

    @PostConstruct
    void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("app.groq.api-key не задан");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("app.groq.base-url не задан");
        }
        if (llmModel == null || llmModel.isBlank()) {
            throw new IllegalStateException("app.groq.llm-model не задан");
        }
    }
}
