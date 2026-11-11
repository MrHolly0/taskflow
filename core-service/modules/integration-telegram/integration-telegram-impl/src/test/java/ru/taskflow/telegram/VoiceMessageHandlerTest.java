package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.telegram.application.ProposalMessageRenderer;
import ru.taskflow.telegram.application.VoiceMessageHandler;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramVoice;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceMessageHandlerTest {
    @org.mockito.Mock
    ru.taskflow.telegram.application.TelegramWidgetGateway widgets;


    @Mock
    private TelegramApiClient apiClient;
    @Mock
    private TelegramMessageSender sender;
    @Mock
    private AssistantService assistantService;
    @Mock
    private ProposalMessageRenderer renderer;

    @Test
    void handleVoice_createsProposalFromTranscript() {
        var handler = new VoiceMessageHandler(apiClient, sender, assistantService, renderer,
                new ru.taskflow.telegram.application.TelegramUpdateCheckpoint(null), widgets);
        var userId = UUID.randomUUID();
        var message = new TelegramMessage(
                1L, new TelegramUser(1L, "u", "Name", null),
                new TelegramChat(100L), null, new TelegramVoice("file123", 5));
        byte[] audio = {1, 2, 3};
        OffsetDateTime now = OffsetDateTime.now();
        Proposal proposal = new Proposal(UUID.randomUUID(), "ABCDEFGH", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), now, now.plusHours(24));

        when(apiClient.downloadVoice("file123")).thenReturn(audio);
        when(assistantService.handleVoice(userId, audio, AssistantChannel.TELEGRAM,
                ru.taskflow.assistant.api.AssistantEntryPoint.CHAT, null)).thenReturn(proposal);
        when(renderer.render(proposal)).thenReturn("Предлагаю: закрыть молоко");
        when(renderer.keyboard(proposal)).thenReturn(List.of(List.of()));

        handler.handle(message, userId);

        verify(apiClient).downloadVoice(eq("file123"));
        verify(sender).sendMessage(eq(100L), eq("Предлагаю: закрыть молоко"), any());
    }
}
