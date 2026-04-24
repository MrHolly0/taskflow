package ru.taskflow.nlp.infrastructure.yandex;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.yandex-gpt")
@Data
public class YandexGptConfig {
    private String apiKey;
    private String baseUrl = "https://llm.api.cloud.yandex.net/llm/v1";
    private String model = "yandexgpt-3";
    private String folderId;
    private boolean enabled = false;
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 30;
}
