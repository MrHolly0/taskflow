package ru.taskflow.nlp.infrastructure.config;

import org.junit.jupiter.api.Test;
import ru.taskflow.nlp.domain.RawToolCall;
import ru.taskflow.nlp.domain.ToolCallMessage;
import ru.taskflow.nlp.domain.ToolCallProvider;
import ru.taskflow.nlp.domain.ToolCallRequest;
import ru.taskflow.nlp.domain.ToolCallResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FallbackToolCallProviderTest {

    private final ToolCallRequest request = new ToolCallRequest(List.of(ToolCallMessage.user("текст")), List.of());

    @Test
    void call_returnsResultFromFirstProviderWhenItSucceeds() {
        ToolCallProvider primary = mock(ToolCallProvider.class);
        ToolCallProvider secondary = mock(ToolCallProvider.class);
        ToolCallResult expected = new ToolCallResult(
                List.of(new RawToolCall("call_1", "create_task", "{}")), null, 10, 5);
        when(primary.call(request)).thenReturn(expected);

        ToolCallResult result = new FallbackToolCallProvider(List.of(primary, secondary)).call(request);

        assertThat(result).isEqualTo(expected);
        verify(secondary, never()).call(any());
    }

    @Test
    void call_doesNotFallBackOnLegitimateEmptyResult() {
        // Пустой ToolCallResult с текстом (уточняющий вопрос) или вовсе без
        // ничего — валидный ответ модели, не отказ. Второй провайдер вызываться
        // не должен, иначе штатная деградация "нечего делать" жгла бы квоту
        // резервного поставщика на каждом таком ответе.
        ToolCallProvider primary = mock(ToolCallProvider.class);
        ToolCallProvider secondary = mock(ToolCallProvider.class);
        ToolCallResult emptyButLegit = new ToolCallResult(List.of(), "уточните, что вы имеете в виду", 8, 3);
        when(primary.call(request)).thenReturn(emptyButLegit);

        ToolCallResult result = new FallbackToolCallProvider(List.of(primary, secondary)).call(request);

        assertThat(result).isEqualTo(emptyButLegit);
        verify(secondary, never()).call(any());
    }

    @Test
    void call_fallsBackToNextProviderOnException() {
        ToolCallProvider primary = mock(ToolCallProvider.class);
        ToolCallProvider secondary = mock(ToolCallProvider.class);
        when(primary.name()).thenReturn("groq");
        when(primary.call(request)).thenThrow(new RuntimeException("недоступен"));
        ToolCallResult expected = new ToolCallResult(List.of(), "ответ от резервного", 1, 1);
        when(secondary.call(request)).thenReturn(expected);

        ToolCallResult result = new FallbackToolCallProvider(List.of(primary, secondary)).call(request);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void call_throwsWhenAllProvidersFail() {
        ToolCallProvider primary = mock(ToolCallProvider.class);
        ToolCallProvider secondary = mock(ToolCallProvider.class);
        when(primary.name()).thenReturn("groq");
        when(secondary.name()).thenReturn("proxyapi");
        when(primary.call(request)).thenThrow(new RuntimeException("недоступен"));
        when(secondary.call(request)).thenThrow(new RuntimeException("тоже недоступен"));

        assertThatThrownBy(() -> new FallbackToolCallProvider(List.of(primary, secondary)).call(request))
                .isInstanceOf(ToolCallProvidersUnavailableException.class);
        verify(primary).call(request);
        verify(secondary).call(request);
    }

    @Test
    void call_throwsForEmptyProviderList() {
        assertThatThrownBy(() -> new FallbackToolCallProvider(List.of()).call(request))
                .isInstanceOf(ToolCallProvidersUnavailableException.class);
    }

    @org.junit.jupiter.api.Test void expiredOperationDoesNotStartProviderFallback() {
        var first=org.mockito.Mockito.mock(ru.taskflow.nlp.domain.ToolCallProvider.class);
        try(var scope=ru.taskflow.shared.resources.ModelRequestContext.open(new ru.taskflow.shared.resources.ModelRequestContext(
                java.util.UUID.randomUUID(),System.currentTimeMillis()-1,0))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->new FallbackToolCallProvider(java.util.List.of(first))
                    .call(new ru.taskflow.nlp.domain.ToolCallRequest(java.util.List.of(),java.util.List.of())))
                    .isInstanceOf(ru.taskflow.shared.resources.ResourceRejectedException.class);
            org.mockito.Mockito.verifyNoInteractions(first);
        }
    }
}
