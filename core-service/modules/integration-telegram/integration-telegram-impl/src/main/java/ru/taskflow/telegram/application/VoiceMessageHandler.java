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
    private final TelegramResourceFeedback resources;
    private final ru.taskflow.shared.resources.ResourcePolicy policy;

    public void handle(TelegramMessage message, UUID userId) {
        String fileId = message.voice().fileId();
        if(message.voice().duration()>policy.getMaxAudioSeconds()) {
            sender.sendMessage(message.chat().id(),"Голосовое длиннее двух минут, раздели его на несколько сообщений");return;
        }
        Proposal proposal=resources.run(message.chat().id(),()->checkpoint.once("proposal",Proposal.class,
                ()->assistantService.handleVoiceUpload(userId,()->apiClient.downloadVoice(fileId),
                        AssistantChannel.TELEGRAM,AssistantEntryPoint.CHAT,checkpoint.requestKey())));
        if(proposal==null) return;
        if(proposal.id()!=null && message.chat().id()==message.from().id()) {
            var resolved=checkpoint.once("auto-apply",ru.taskflow.assistant.api.dto.AssistantResult.class,
                    ()->assistantService.resolveAutomatically(userId,proposal.id()));
            if(resolved!=null && resolved.applied()!=null && widgets.sendApplied(userId,message.chat().id(),resolved.proposal(),resolved.applied())) return;
        }

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
