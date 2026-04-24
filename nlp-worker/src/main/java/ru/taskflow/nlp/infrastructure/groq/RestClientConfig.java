package ru.taskflow.nlp.infrastructure.groq;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final GroqConfig groqConfig;

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(groqConfig.getConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(groqConfig.getReadTimeoutSeconds() * 1000);

        return RestClient.builder()
            .baseUrl(groqConfig.getBaseUrl())
            .requestFactory(factory)
            .build();
    }
}
