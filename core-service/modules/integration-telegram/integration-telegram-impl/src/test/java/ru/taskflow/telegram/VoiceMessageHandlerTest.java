package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.telegram.application.VoiceMessageHandler;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramVoice;

import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceMessageHandlerTest {

    @Mock
    private TelegramApiClient apiClient;

    @Mock
    private TelegramMessageSender sender;

    @TempDir
    Path tempDir;

    @Test
    void voiceMessage_downloadsFile_andSendsAck() {
        var handler = new VoiceMessageHandler(apiClient, sender, tempDir.toString());
        var message = new TelegramMessage(
                1L, new TelegramUser(1L, "u", "Name", null),
                new TelegramChat(100L), null, new TelegramVoice("file123", 5));
        when(apiClient.downloadVoice("file123")).thenReturn(new byte[]{1, 2, 3});

        handler.handle(message, UUID.randomUUID());

        verify(apiClient).downloadVoice(eq("file123"));
        verify(sender).sendMessage(eq(100L), any(String.class));
    }
}