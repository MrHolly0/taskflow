package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;

import java.util.UUID;

/**
 * Обработчик слеш-команд Telegram бота.
 *
 * Поддерживает команды /start, /help, /today и возвращает соответствующие
 * ответы с информацией о боте и списком задач пользователя.
 */
@Service
@RequiredArgsConstructor
public class BotCommandRouter {

    private final TaskService taskService;
    private final TelegramMessageSender sender;

    @Value("${app.telegram.miniapp-url:}")
    private String miniappUrl;

    public void handle(TelegramMessage message, UUID userId) {
        String command = parseCommand(message.text());
        long chatId = message.chat().id();

        switch (command) {
            case "/start" -> sendWelcome(chatId, message.from().firstName());
            case "/help" -> sendHelp(chatId);
            case "/today", "/week" -> sendTaskList(chatId, userId);
            default -> sendHelp(chatId);
        }
    }

    private void sendWelcome(long chatId, String firstName) {
        String text = """
                Привет, %s! 👋

                Я TaskFlow — помогаю управлять задачами. Просто напишите задачу, и я её сохраню.

                /today — ваши задачи
                /help — помощь
                """.formatted(firstName);

        if (!miniappUrl.isBlank()) {
            sender.sendMessageWithWebApp(chatId, text, "📱 Открыть TaskFlow", miniappUrl);
        } else {
            sender.sendMessage(chatId, text);
        }
    }

    private void sendHelp(long chatId) {
        sender.sendMessage(chatId, """
                Команды:
                /today — список активных задач
                /help — это сообщение

                Как создавать задачи:
                📝 Напишите текст: "Купить хлеба до вечера"
                🎤 Отправьте голосовое сообщение — бот распознает речь и создаст задачу

                Примеры:
                • "Купить молоко, хлеб, масло"
                • "Позвонить маме завтра в 18:00"
                • "Написать отчёт до пятницы"
                """);
    }

    private void sendTaskList(long chatId, UUID userId) {
        Page<TaskResponse> tasks = taskService.findAll(
                userId,
                new TaskFilterRequest(null, TaskStatus.TODO, null, null),
                PageRequest.of(0, 10));

        if (tasks.isEmpty()) {
            sender.sendMessage(chatId, "Активных задач нет.");
            return;
        }

        var sb = new StringBuilder("<b>Ваши задачи:</b>\n\n");
        int i = 1;
        for (TaskResponse t : tasks) {
            sb.append(i++).append(". ").append(t.title()).append("\n");
        }
        sender.sendMessage(chatId, sb.toString());
    }

    private static String parseCommand(String text) {
        if (text == null) return "";
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space);
    }
}