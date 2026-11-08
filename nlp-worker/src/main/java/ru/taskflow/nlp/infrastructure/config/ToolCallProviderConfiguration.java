package ru.taskflow.nlp.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import ru.taskflow.nlp.domain.ToolCallProvider;
import ru.taskflow.nlp.infrastructure.groq.OpenAiCompatibleToolCallProvider;

import java.util.List;

/**
 * Список провайдеров tool calling задаётся конфигурацией (app.llm.providers),
 * а не кодом — порядок в списке определяет порядок обращения при отказе.
 * Подключение нового поставщика (например ProxyAPI) — это правка
 * application.yml и ключ в .env, кода не касается.
 */
@Configuration
@RequiredArgsConstructor
public class ToolCallProviderConfiguration {

    private final ToolCallProvidersConfig providersConfig;
    private final ObjectMapper objectMapper;
    private final ru.taskflow.shared.resources.GuardedProviderInterceptor guard;
    private final ru.taskflow.shared.resources.ResourcePolicy policy;

    @Bean
    public ToolCallProvider toolCallProvider() {
        List<ToolCallProvider> providers = providersConfig.getProviders().stream()
                .filter(ToolCallProvidersConfig.Entry::isEnabled)
                .peek(this::validate)
                .map(this::buildProvider)
                .toList();

        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "app.llm.providers не содержит ни одного включённого провайдера tool calling");
        }

        return new FallbackToolCallProvider(providers);
    }

    private void validate(ToolCallProvidersConfig.Entry entry) {
        String label = entry.getName() != null && !entry.getName().isBlank() ? entry.getName() : "<без имени>";
        requireNonBlank(entry.getName(), "name", label);
        requireNonBlank(entry.getBaseUrl(), "base-url", label);
        requireNonBlank(entry.getApiKey(), "api-key", label);
        requireNonBlank(entry.getModel(), "model", label);
    }

    private void requireNonBlank(String value, String field, String providerLabel) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "app.llm.providers: у провайдера \"" + providerLabel + "\" не задано поле " + field);
        }
    }

    private ToolCallProvider buildProvider(ToolCallProvidersConfig.Entry entry) {
        RestClient restClient = RestClient.builder()
                .baseUrl(entry.getBaseUrl())
                .requestFactory(new ru.taskflow.shared.resources.DeadlineRequestFactory(policy.getExternalTimeoutMs()))
                .requestInterceptor(guard)
                .build();
        return new OpenAiCompatibleToolCallProvider(entry.getName(), entry.getApiKey(), entry.getModel(),
                objectMapper, restClient);
    }

}
