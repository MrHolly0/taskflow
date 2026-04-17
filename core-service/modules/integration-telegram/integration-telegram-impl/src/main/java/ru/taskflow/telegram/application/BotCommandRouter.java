package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class BotCommandRouter {

    private final TaskService taskService;
    private final TelegramMessageSender sender;

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
        sender.sendMessage(chatId, """
                Привет, %s!

                Я TaskFlow — помогаю управлять задачами. Просто напишите задачу, и я её сохраню.

                /today — ваши задачи
                /help — помощь
                """.formatted(firstName));
    }

    private void sendHelp(long chatId) {
        sender.sendMessage(chatId, """
                Команды:
                /today — список активных задач
                /help — это сообщение

                Или просто напишите текст задачи.
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