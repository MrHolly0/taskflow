package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.nlp.api.NlpParsedTask;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender.InlineButton;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceMessageHandler {

    private final TelegramApiClient apiClient;
    private final TelegramMessageSender sender;
    private final TaskService taskService;
    private final NlpGatewayService nlpGatewayService;

    public void handle(TelegramMessage message, UUID userId) {
        String fileId = message.voice().fileId();
        byte[] data = apiClient.downloadVoice(fileId);

        sender.sendMessage(message.chat().id(), "🎤 Распознаю голосовое сообщение...");

        String userTimezone = "Europe/Moscow";

        try {
            var parseResult = nlpGatewayService.parseVoice(data, userTimezone);

            if (parseResult.tasks().isEmpty()) {
                sender.sendMessage(message.chat().id(), "Не удалось распознать голосовое сообщение. Попробуйте текстом.");
                return;
            }

            List<TaskResponse> createdTasks = new ArrayList<>();
            for (NlpParsedTask parsed : parseResult.tasks()) {
                try {
                    TaskPriority priority = parsePriority(parsed.priority());
                    OffsetDateTime deadline = parsed.deadline() != null
                        ? OffsetDateTime.ofInstant(parsed.deadline(), ZoneOffset.UTC)
                        : null;

                    var request = new CreateTaskRequest(
                        parsed.title(),
                        parsed.description(),
                        priority,
                        deadline,
                        null,
                        parsed.tags(),
                        null,
                        TaskSource.BOT_VOICE
                    );
                    TaskResponse task = taskService.create(userId, request);
                    createdTasks.add(task);
                } catch (Exception e) {
                    log.error("Failed to create task from voice parse result", e);
                }
            }

            if (createdTasks.isEmpty()) {
                sender.sendMessage(message.chat().id(), "Ошибка при создании задач. Попробуйте позже.");
                return;
            }

            String responseText = "🎤 <b>Распознано задач: " + createdTasks.size() + "</b>\n\n";
            for (TaskResponse task : createdTasks) {
                responseText += "▪️ <b>" + task.title() + "</b>\n";
            }
            responseText += "\nПодтвердите или отредактируйте:";

            var keyboard = new ArrayList<List<InlineButton>>();
            for (TaskResponse task : createdTasks) {
                keyboard.add(List.of(
                    new InlineButton("✅", "confirm:" + task.id()),
                    new InlineButton("✏️", "edit:" + task.id())
                ));
            }
            sender.sendMessage(message.chat().id(), responseText, keyboard);
        } catch (Exception e) {
            log.error("Failed to process voice message", e);
            sender.sendMessage(message.chat().id(), "Ошибка при обработке голоса. Попробуйте позже.");
        }
    }

    private TaskPriority parsePriority(String priority) {
        if (priority == null) return TaskPriority.MEDIUM;
        return switch (priority.toUpperCase()) {
            case "LOW" -> TaskPriority.LOW;
            case "HIGH" -> TaskPriority.HIGH;
            case "URGENT" -> TaskPriority.URGENT;
            default -> TaskPriority.MEDIUM;
        };
    }
}