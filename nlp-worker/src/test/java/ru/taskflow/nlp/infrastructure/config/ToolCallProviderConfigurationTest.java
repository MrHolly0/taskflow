package ru.taskflow.nlp.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.nlp.domain.ToolCallProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallProviderConfigurationTest {

    private ToolCallProviderConfiguration configuration(ToolCallProvidersConfig.Entry... entries) {
        ToolCallProvidersConfig config = new ToolCallProvidersConfig();
        config.setProviders(List.of(entries));
        return new ToolCallProviderConfiguration(config, new ObjectMapper(),
                org.mockito.Mockito.mock(ru.taskflow.shared.resources.GuardedProviderInterceptor.class),
                new ru.taskflow.shared.resources.ResourcePolicy());
    }

    private ToolCallProvidersConfig.Entry entry(String name, String baseUrl, String apiKey, String model, boolean enabled) {
        ToolCallProvidersConfig.Entry entry = new ToolCallProvidersConfig.Entry();
        entry.setName(name);
        entry.setBaseUrl(baseUrl);
        entry.setApiKey(apiKey);
        entry.setModel(model);
        entry.setEnabled(enabled);
        return entry;
    }

    @Test
    void toolCallProvider_buildsFromEnabledEntry() {
        ToolCallProvider provider = configuration(
                entry("groq", "https://api.groq.com/openai/v1", "key", "model", true)
        ).toolCallProvider();

        assertThat(provider).isNotNull();
    }

    @Test
    void toolCallProvider_skipsDisabledEntryEvenIfInvalid() {
        // Выключенный провайдер не должен валить старт незаполненными полями —
        // это заготовка на будущее (см. proxyapi с enabled: false в примере плана).
        ToolCallProvider provider = configuration(
                entry("groq", "https://api.groq.com/openai/v1", "key", "model", true),
                entry("proxyapi", null, null, null, false)
        ).toolCallProvider();

        assertThat(provider).isNotNull();
    }

    @Test
    void toolCallProvider_throwsWhenEnabledEntryMissingApiKey() {
        assertThatThrownBy(() -> configuration(
                entry("groq", "https://api.groq.com/openai/v1", "", "model", true)
        ).toolCallProvider())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("groq")
                .hasMessageContaining("api-key");
    }

    @Test
    void toolCallProvider_throwsWhenNoEnabledProviders() {
        assertThatThrownBy(() -> configuration().toolCallProvider())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.llm.providers");
    }
}
