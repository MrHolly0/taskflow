package ru.taskflow.telegram.infrastructure.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramMessageSender {

    private final TelegramApiClient apiClient;

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 500))
    public void sendMessage(long chatId, String text) {
        apiClient.sendMessage(chatId, text, "HTML");
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 500))
    public void sendMessage(long chatId, String text, List<List<InlineButton>> keyboard) {
        var rows = keyboard.stream()
                .map(row -> row.stream()
                        .map(b -> Map.of("text", b.text(), "callback_data", b.callbackData()))
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
        var replyMarkup = Map.of("inline_keyboard", rows);
        apiClient.sendMessageWithKeyboard(chatId, text, "HTML", replyMarkup);
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 500))
    public void answerCallback(String callbackQueryId, String text) {
        apiClient.answerCallbackQuery(callbackQueryId, text);
    }

    public void sendMessageWithWebApp(long chatId, String text, String buttonText, String webAppUrl) {
        var button = Map.of("text", buttonText, "web_app", Map.of("url", webAppUrl));
        var replyMarkup = Map.of("inline_keyboard", List.of(List.of(button)));
        apiClient.sendMessageWithKeyboard(chatId, text, "HTML", replyMarkup);
    }

    public record InlineButton(String text, String callbackData) {}
}