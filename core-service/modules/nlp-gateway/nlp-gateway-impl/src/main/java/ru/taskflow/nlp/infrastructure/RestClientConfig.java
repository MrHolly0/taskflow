package ru.taskflow.nlp.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final NlpGatewayConfig nlpGatewayConfig;

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(nlpGatewayConfig.getConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(nlpGatewayConfig.getReadTimeoutSeconds() * 1000);

        return RestClient.builder()
            .requestFactory(factory)
            .build();
    }
}
