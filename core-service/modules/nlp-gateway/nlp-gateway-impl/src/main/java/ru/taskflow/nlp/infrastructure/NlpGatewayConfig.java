package ru.taskflow.nlp.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.nlp")
@Data
public class NlpGatewayConfig {
    private String workerUrl = "http://localhost:8081";
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 30;
}
