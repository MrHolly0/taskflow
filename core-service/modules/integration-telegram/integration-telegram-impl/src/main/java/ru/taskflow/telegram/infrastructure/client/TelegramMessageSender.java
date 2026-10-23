package ru.taskflow.telegram.infrastructure.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramMessageSender {

    private final TelegramApiClient apiClient;
    private final TelegramRequestRetry retry;

    public void sendMessage(long chatId, String text) {
        retry.execute(() -> apiClient.sendMessage(chatId, text, "HTML"));
    }

    public void sendMessage(long chatId, String text, List<List<InlineButton>> keyboard) {
        retry.execute(() -> apiClient.sendMessageWithKeyboard(chatId, text, "HTML", replyMarkup(keyboard)));
    }

    public void editMessage(long chatId, long messageId, String text) {
        retry.execute(() -> apiClient.editMessageText(chatId, messageId, text, "HTML"));
    }

    public void editMessage(long chatId, long messageId, String text, List<List<InlineButton>> keyboard) {
        retry.execute(() -> apiClient.editMessageTextWithKeyboard(chatId, messageId, text, "HTML", replyMarkup(keyboard)));
    }

    public void answerCallback(String callbackQueryId, String text) {
        try {
            retry.execute(() -> apiClient.answerCallbackQuery(callbackQueryId, text));
        } catch (HttpClientErrorException.BadRequest e) {
            String body = e.getResponseBodyAsString();
            if (!body.contains("query is too old") && !body.contains("query ID is invalid")) throw e;
            log.debug("Telegram callback expired before acknowledgement");
        }
    }

    private Map<String, Object> replyMarkup(List<List<InlineButton>> keyboard) {
        var rows = keyboard.stream()
                .map(row -> row.stream()
                        .map(b -> Map.of("text", b.text(), "callback_data", b.callbackData()))
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
        return Map.of("inline_keyboard", rows);
    }

    public void sendMessageWithWebApp(long chatId, String text, String buttonText, String webAppUrl) {
        var button = Map.of("text", buttonText, "web_app", Map.of("url", webAppUrl));
        var replyMarkup = Map.of("inline_keyboard", List.of(List.of(button)));
        retry.execute(() -> apiClient.sendMessageWithKeyboard(chatId, text, "HTML", replyMarkup));
    }

    public record InlineButton(String text, String callbackData) {}
}