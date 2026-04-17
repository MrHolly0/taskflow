package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramCallbackQuery;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CallbackHandler {

    private final TaskService taskService;
    private final TelegramMessageSender sender;

    public void handle(TelegramCallbackQuery callback, UUID userId) {
        String data = callback.data();
        if (data == null || !data.contains(":")) {
            sender.answerCallback(callback.id(), "Неизвестная команда");
            return;
        }

        int sep = data.indexOf(':');
        String action = data.substring(0, sep);
        String payload = data.substring(sep + 1);

        switch (action) {
            case "complete" -> {
                taskService.complete(userId, UUID.fromString(payload));
                sender.answerCallback(callback.id(), "✅ Выполнено!");
            }
            case "delete" -> {
                taskService.delete(userId, UUID.fromString(payload));
                sender.answerCallback(callback.id(), "🗑 Удалено");
            }
            default -> sender.answerCallback(callback.id(), "Неизвестная команда");
        }
    }
}