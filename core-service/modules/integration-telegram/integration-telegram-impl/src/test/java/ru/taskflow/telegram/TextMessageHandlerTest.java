package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import ru.taskflow.telegram.application.TelegramUpdateCheckpoint;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.FastPathResolver;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.telegram.application.ProposalMessageRenderer;
import ru.taskflow.telegram.application.TextMessageHandler;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextMessageHandlerTest {
    @Mock ru.taskflow.telegram.application.TelegramResourceFeedback resources;
    @org.junit.jupiter.api.BeforeEach void permitResource() {
        org.mockito.Mockito.lenient().when(resources.run(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any()))
          .thenAnswer(call->call.<java.util.function.Supplier<Object>>getArgument(1).get());
    }
    @org.mockito.Mock
    ru.taskflow.telegram.application.TelegramWidgetGateway widgets;


    @Mock
    private AssistantService assistantService;

    @Mock
    private FastPathResolver fastPathResolver;

    @Mock
    private ProposalMessageRenderer renderer;

    @Mock
    private TelegramMessageSender sender;

    @Spy
    private TelegramUpdateCheckpoint checkpoint = new TelegramUpdateCheckpoint(null);

    @InjectMocks
    private TextMessageHandler handler;

    private final UUID userId = UUID.randomUUID();

    @Test
    void handleText_createsProposalAndSendsMessage() {
        var message = message("закрой молоко");
        Proposal proposal = proposal();
        when(assistantService.findLatestPending(userId)).thenReturn(Optional.empty());
        when(fastPathResolver.resolve("закрой молоко", false)).thenReturn(Optional.empty());
        when(assistantService.handleText(userId, "закрой молоко", AssistantChannel.TELEGRAM,
                ru.taskflow.assistant.api.AssistantEntryPoint.CHAT, null)).thenReturn(proposal);
        when(renderer.render(proposal)).thenReturn("Предлагаю: закрыть молоко");
        when(renderer.keyboard(proposal)).thenReturn(List.of(List.of()));

        handler.handle(message, userId);

        verify(sender).sendMessage(eq(100L), eq("Предлагаю: закрыть молоко"), any());
    }

    @Test
    void handleText_usesFastPathWithoutLlm() {
        var message = message("да");
        Proposal pending = proposal();
        when(assistantService.findLatestPending(userId)).thenReturn(Optional.of(pending));
        when(fastPathResolver.resolve("да", true)).thenReturn(Optional.of(true));
        when(assistantService.apply(userId, pending.id()))
                .thenReturn(new ApplyResult(ProposalStatus.APPLIED, 1, 1, List.of()));
        when(renderer.renderApplyResult(any())).thenReturn("Применено 1 из 1.");

        handler.handle(message, userId);

        verify(assistantService, never()).handleText(any(), any(), any());
        verify(sender).sendMessage(eq(100L), eq("Применено 1 из 1."));
    }

    private static TelegramMessage message(String text) {
        return new TelegramMessage(1L, new TelegramUser(1L, "user", "Name", null),
                new TelegramChat(100L), text, null);
    }

    private Proposal proposal() {
        OffsetDateTime now = OffsetDateTime.now();
        return new Proposal(UUID.randomUUID(), "ABCDEFGH", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), now, now.plusHours(24));
    }
}
