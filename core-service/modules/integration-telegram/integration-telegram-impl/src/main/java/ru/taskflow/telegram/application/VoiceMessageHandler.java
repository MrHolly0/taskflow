package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;

import java.util.List;
import java.util.UUID;

/**
 * Обработчик голосовых сообщений в Telegram боте.
 *
 * Расшифровка и разбор — целиком на стороне AssistantService.handleVoice,
 * здесь только скачивание файла и рендер предложения.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceMessageHandler {

    private final TelegramApiClient apiClient;
    private final TelegramMessageSender sender;
    private final AssistantService assistantService;
    private final ProposalMessageRenderer renderer;
    private final TelegramUpdateCheckpoint checkpoint;
    private final TelegramWidgetGateway widgets;

    public void handle(TelegramMessage message, UUID userId) {
        String fileId = message.voice().fileId();
        Proposal proposal = checkpoint.once("proposal", Proposal.class,
                () -> assistantService.handleVoice(userId, apiClient.downloadVoice(fileId),
                        AssistantChannel.TELEGRAM, AssistantEntryPoint.CHAT, checkpoint.requestKey()));

        if (widgets.sendProposal(userId,message.chat().id(),proposal)) return;
        String text = renderer.render(proposal);
        List<List<TelegramMessageSender.InlineButton>> keyboard = renderer.keyboard(proposal);
        if (keyboard.isEmpty()) {
            sender.sendMessage(message.chat().id(), text);
        } else {
            sender.sendMessage(message.chat().id(), text, keyboard);
        }
    }
}
