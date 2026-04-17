package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender.InlineButton;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TextMessageHandler {

    private final TaskService taskService;
    private final TelegramMessageSender sender;

    public void handle(TelegramMessage message, UUID userId) {
        var request = new CreateTaskRequest(message.text(), null, null, null, null, null, null, TaskSource.BOT_TEXT);
        TaskResponse task = taskService.create(userId, request);

        var keyboard = List.of(List.of(
                new InlineButton("✅ Выполнить", "complete:" + task.id()),
                new InlineButton("🗑 Удалить", "delete:" + task.id())
        ));
        sender.sendMessage(message.chat().id(), "Задача создана: <b>" + task.title() + "</b>", keyboard);
    }
}