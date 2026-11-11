package ru.taskflow.telegram.application;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import java.util.function.Supplier;

@Component
public class TelegramResourceFeedback {
    private final TelegramMessageSender sender;
    public TelegramResourceFeedback(TelegramMessageSender sender) { this.sender=sender; }
    public <T> T run(long chatId,Supplier<T> operation) {
        try { return operation.get(); }
        catch (ResponseStatusException failure) {
            int status=failure.getStatusCode().value();
            if(status!=413 && status!=429 && status!=503) throw failure;
            sender.sendMessage(chatId,switch(status) {
                case 413 -> "Сообщение слишком большое, отправь текст короче или голосовое до двух минут";
                case 429 -> "Лимит обращений пока исчерпан, попробуй позже";
                default -> "Мунин сейчас занят, попробуй чуть позже";
            });
            return null;
        }
    }
}
