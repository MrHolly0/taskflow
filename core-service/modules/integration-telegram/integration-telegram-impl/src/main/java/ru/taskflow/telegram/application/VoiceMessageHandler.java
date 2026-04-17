package ru.taskflow.telegram.application;

import lombok.extern.slf4j.Slf4j;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
public class VoiceMessageHandler {

    private final TelegramApiClient apiClient;
    private final TelegramMessageSender sender;
    private final String voiceDir;

    public VoiceMessageHandler(TelegramApiClient apiClient, TelegramMessageSender sender, String voiceDir) {
        this.apiClient = apiClient;
        this.sender = sender;
        this.voiceDir = voiceDir;
    }

    public void handle(TelegramMessage message, UUID userId) {
        String fileId = message.voice().fileId();
        byte[] data = apiClient.downloadVoice(fileId);

        try {
            Path dir = Path.of(voiceDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(message.messageId() + "_" + fileId + ".ogg");
            Files.write(file, data);
            log.info("Voice saved: {}", file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        sender.sendMessage(message.chat().id(), "Голосовое сообщение получено, обработка добавлена в очередь.");
    }
}