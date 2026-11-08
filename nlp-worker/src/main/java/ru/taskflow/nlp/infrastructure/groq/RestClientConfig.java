package ru.taskflow.nlp.infrastructure.groq;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final GroqConfig groqConfig;
    private final ru.taskflow.shared.resources.GuardedProviderInterceptor guard;
    private final ru.taskflow.shared.resources.ResourcePolicy policy;

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
            .baseUrl(groqConfig.getBaseUrl())
            .requestFactory(new ru.taskflow.shared.resources.DeadlineRequestFactory(policy.getExternalTimeoutMs()))
            .requestInterceptor(guard)
            .build();
    }
}
