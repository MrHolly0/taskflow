package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.FastPathResolver;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Обработчик текстовых сообщений в Telegram боте.
 *
 * Худеет до пересылки ассистенту: разбор и создание задач теперь целиком
 * на стороне AssistantService, здесь — только рендер предложения и быстрый
 * путь подтверждения без обращения к модели.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TextMessageHandler {

    private final AssistantService assistantService;
    private final FastPathResolver fastPathResolver;
    private final ProposalMessageRenderer renderer;
    private final TelegramMessageSender sender;
    private final TelegramUpdateCheckpoint checkpoint;
    private final TelegramWidgetGateway widgets;

    public void handle(TelegramMessage message, UUID userId) {
        MessageDecision decision = checkpoint.once("decision", MessageDecision.class, () -> {
            Optional<Proposal> pending = assistantService.findLatestPending(userId);
            Optional<Boolean> fastPath = fastPathResolver.resolve(message.text(), pending.isPresent());
            return new MessageDecision(fastPath.isPresent() ? pending.orElseThrow().id() : null,
                    fastPath.orElse(null));
        });
        if (decision.proposalId() != null) {
            resolveFastPath(userId, decision.proposalId(), decision.accepted(), message.chat().id());
            return;
        }
        Proposal proposal = checkpoint.once("proposal", Proposal.class,
                () -> assistantService.handleText(userId, message.text(), AssistantChannel.TELEGRAM,
                        AssistantEntryPoint.CHAT, checkpoint.requestKey()));
        send(userId,message.chat().id(), proposal);
    }

    public record MessageDecision(UUID proposalId, Boolean accepted) {}

    private void resolveFastPath(UUID userId, UUID proposalId, boolean accepted, long chatId) {
        if (accepted) {
            ApplyResult result = checkpoint.once("apply", ApplyResult.class,
                    () -> assistantService.apply(userId, proposalId));
            widgets.refreshProposal(userId,proposalId);
            sender.sendMessage(chatId, renderer.renderApplyResult(result));
        } else {
            assistantService.reject(userId, proposalId);
            widgets.refreshProposal(userId,proposalId);
            sender.sendMessage(chatId, "Предложение отклонено.");
        }
    }

    private void send(UUID userId,long chatId, Proposal proposal) {
        if (widgets.sendProposal(userId,chatId,proposal)) return;
        String text = renderer.render(proposal);
        List<List<TelegramMessageSender.InlineButton>> keyboard = renderer.keyboard(proposal);
        if (keyboard.isEmpty()) {
            sender.sendMessage(chatId, text);
        } else {
            sender.sendMessage(chatId, text, keyboard);
        }
    }
}
