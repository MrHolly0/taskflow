package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import ru.taskflow.telegram.application.TelegramUpdateCheckpoint;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.assistant.api.exception.ProposalNotFoundException;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.telegram.application.CallbackHandler;
import ru.taskflow.telegram.application.ProposalMessageRenderer;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramCallbackQuery;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CallbackHandlerTest {

    @Mock
    private TaskService taskService;

    @Mock
    private AssistantService assistantService;

    @Mock
    private ProposalMessageRenderer renderer;

    @Mock
    private TelegramMessageSender sender;

    @Spy
    private TelegramUpdateCheckpoint checkpoint = new TelegramUpdateCheckpoint(null);

    @InjectMocks
    private CallbackHandler handler;

    @Test
    void completeCallback_completesTask() {
        var userId = UUID.randomUUID();
        var taskId = UUID.randomUUID();

        handler.handle(callback("complete:" + taskId), userId);

        verify(taskService).complete(userId, taskId);
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void deleteCallback_deletesTask() {
        var userId = UUID.randomUUID();
        var taskId = UUID.randomUUID();

        handler.handle(callback("delete:" + taskId), userId);

        verify(taskService).delete(userId, taskId);
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void invalidCallbackData_answersWithError() {
        handler.handle(callback("garbage"), UUID.randomUUID());

        verifyNoInteractions(taskService);
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void confirmCallback_noLongerRecognized_doesNotThrow() {
        handler.handle(callback("confirm:" + UUID.randomUUID()), UUID.randomUUID());

        verifyNoInteractions(taskService);
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void callback_applyAppliesProposal() {
        var userId = UUID.randomUUID();
        var proposalId = UUID.randomUUID();
        var proposal = proposal(proposalId, "SHORT123", List.of());
        when(assistantService.findByShortCode(userId, "SHORT123")).thenReturn(proposal);
        when(assistantService.apply(userId, proposalId))
                .thenReturn(new ApplyResult(ProposalStatus.APPLIED, 1, 1, List.of()));
        when(renderer.renderApplyResult(any())).thenReturn("Применено 1 из 1.");

        handler.handle(callback("p:SHORT123:a"), userId);

        verify(assistantService).apply(userId, proposalId);
        verify(sender).editMessage(eq(100L), eq(1L), eq("Применено 1 из 1."));
    }

    @Test
    void callback_rejectRejectsProposal() {
        var userId = UUID.randomUUID();
        var proposalId = UUID.randomUUID();
        var proposal = proposal(proposalId, "SHORT123", List.of());
        when(assistantService.findByShortCode(userId, "SHORT123")).thenReturn(proposal);

        handler.handle(callback("p:SHORT123:r"), userId);

        verify(assistantService).reject(userId, proposalId);
        verify(sender).editMessage(eq(100L), eq(1L), anyString());
    }

    @Test
    void callback_toggleFlipsAction() {
        var userId = UUID.randomUUID();
        var proposalId = UUID.randomUUID();
        var action = new ProposedAction(1, AssistantActionType.COMPLETE, UUID.randomUUID(), Map.of(), "закрыть задачу", true);
        var proposal = proposal(proposalId, "SHORT123", List.of(action));
        when(assistantService.findByShortCode(userId, "SHORT123")).thenReturn(proposal);
        when(assistantService.setActionAccepted(userId, proposalId, 1, false)).thenReturn(proposal);
        when(renderer.render(any())).thenReturn("текст");
        when(renderer.keyboard(any())).thenReturn(List.of(List.of()));

        handler.handle(callback("p:SHORT123:t:1"), userId);

        verify(assistantService).setActionAccepted(userId, proposalId, 1, false);
        verify(sender).editMessage(eq(100L), eq(1L), eq("текст"), any());
    }

    @Test
    void callback_foreignProposalIsIgnored() {
        var userId = UUID.randomUUID();
        when(assistantService.findByShortCode(userId, "SHORT123")).thenThrow(new ProposalNotFoundException(null));

        handler.handle(callback("p:SHORT123:a"), userId);

        verify(assistantService, never()).apply(any(), any());
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void callback_missingSubActionAnswersWithError() {
        var userId = UUID.randomUUID();

        handler.handle(callback("p:SHORT123"), userId);

        verifyNoInteractions(assistantService);
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void callback_missingToggleOrdinalAnswersWithError() {
        var userId = UUID.randomUUID();
        var proposal = proposal(UUID.randomUUID(), "SHORT123", List.of());
        when(assistantService.findByShortCode(userId, "SHORT123")).thenReturn(proposal);

        handler.handle(callback("p:SHORT123:t"), userId);

        verify(assistantService, never()).setActionAccepted(any(), any(), anyInt(), anyBoolean());
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void callback_nonNumericToggleOrdinalAnswersWithError() {
        var userId = UUID.randomUUID();
        var proposal = proposal(UUID.randomUUID(), "SHORT123", List.of());
        when(assistantService.findByShortCode(userId, "SHORT123")).thenReturn(proposal);

        handler.handle(callback("p:SHORT123:t:xyz"), userId);

        verify(assistantService, never()).setActionAccepted(any(), any(), anyInt(), anyBoolean());
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    private static TelegramCallbackQuery callback(String data) {
        var user = new TelegramUser(1L, "user", "Name", null);
        var message = new TelegramMessage(1L, user, new TelegramChat(100L), null, null);
        return new TelegramCallbackQuery("cb1", user, message, data);
    }

    private Proposal proposal(UUID id, String shortCode, List<ProposedAction> actions) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Proposal(id, shortCode, UUID.randomUUID(), ProposalStatus.PENDING,
                "текст", null, actions, now, now.plusHours(24));
    }
}
