package ru.taskflow.nlp.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import ru.taskflow.nlp.domain.ToolCallProvider;
import ru.taskflow.nlp.domain.ToolCallRequest;
import ru.taskflow.nlp.domain.ToolCallResult;

import java.util.List;

/**
 * Перебирает провайдеров в порядке app.llm.providers, переходя к следующему
 * при отказе — как FallbackLlmProvider для старого пути разбора текста.
 * Отличие принципиальное: там «отказ» — это пустой результат (нулевой список
 * задач тоже считается неудачей), а здесь пустой ToolCallResult без вызовов —
 * легитимный ответ модели («ничего делать не нужно» или уточняющий вопрос в
 * text), а не признак поломки. Поэтому сигнал отказа здесь — только
 * исключение из ToolCallProvider.call(), не пустота результата.
 */
@Slf4j
public class FallbackToolCallProvider implements ToolCallProvider {

    private final List<ToolCallProvider> providers;

    public FallbackToolCallProvider(List<ToolCallProvider> providers) {
        this.providers = providers;
    }

    @Override
    public ToolCallResult call(ToolCallRequest req) {
        for (int i = 0; i < providers.size(); i++) {
            ToolCallProvider provider = providers.get(i);
            try {
                ToolCallResult result = provider.call(req);
                if (i > 0) {
                    log.info("{} succeeded as fallback after primary failed", provider.name());
                }
                return result;
            } catch (Exception e) {
                log.warn("{} failed: {}", provider.name(), e.getMessage());
            }
        }
        throw new ToolCallProvidersUnavailableException();
    }
}
