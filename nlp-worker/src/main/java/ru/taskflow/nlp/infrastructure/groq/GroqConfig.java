package ru.taskflow.nlp.infrastructure.groq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.groq")
@Data
public class GroqConfig {
    private String apiKey;
    private String baseUrl = "https://api.groq.com/openai/v1";
    private String llmModel = "llama-3.3-70b-versatile";
    private String whisperModel = "whisper-large-v3";
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 30;
}
