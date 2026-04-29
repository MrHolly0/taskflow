package ru.taskflow.nlp.infrastructure.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import ru.taskflow.nlp.domain.LlmProvider;
import ru.taskflow.nlp.domain.ParsedTasks;
import ru.taskflow.nlp.infrastructure.groq.GroqLlmProvider;
import ru.taskflow.nlp.infrastructure.yandex.YandexGptLlmProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class LlmProviderConfiguration {

    private final GroqLlmProvider groqProvider;
    private final Optional<YandexGptLlmProvider> yandexProvider;

    @Bean
    @Primary
    public LlmProvider llmProvider() {
        return new FallbackLlmProvider(buildProviderList());
    }

    private List<LlmProvider> buildProviderList() {
        List<LlmProvider> providers = new ArrayList<>();
        providers.add(groqProvider);
        yandexProvider.ifPresent(providers::add);
        return providers;
    }

    @Slf4j
    public static class FallbackLlmProvider implements LlmProvider {
        private final List<LlmProvider> providers;

        public FallbackLlmProvider(List<LlmProvider> providers) {
            this.providers = providers;
        }

        @Override
        public ParsedTasks parseTasksFromText(String text, String userTimezone, String userLanguage, List<String> existingGroups) {
            for (int i = 0; i < providers.size(); i++) {
                LlmProvider provider = providers.get(i);
                try {
                    log.debug("Attempting parse with: {}", provider.getClass().getSimpleName());
                    ParsedTasks result = provider.parseTasksFromText(text, userTimezone, userLanguage, existingGroups);
                    if (result != null && !result.tasks().isEmpty()) {
                        if (i > 0) {
                            log.info("{} succeeded as fallback after primary failed", provider.getClass().getSimpleName());
                        }
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("{} failed: {}", provider.getClass().getSimpleName(), e.getMessage());
                    if (i == providers.size() - 1) {
                        log.error("All LLM providers exhausted, returning empty result");
                        return new ParsedTasks(new ArrayList<>());
                    }
                }
            }
            return new ParsedTasks(new ArrayList<>());
        }
    }
}
