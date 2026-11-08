package ru.taskflow.nlp.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;
import ru.taskflow.shared.resources.*;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {
    private final ResourcePolicy policy;
    @Bean public RestClient restClient() { return client(); }
    @Bean public RestClient toolCallRestClient() { return client(); }
    private RestClient client() {
        return RestClient.builder().requestFactory(new DeadlineRequestFactory(policy.getOperationTimeoutMs()))
                .requestInterceptor(new InternalWorkerInterceptor(policy)).build();
    }
}
