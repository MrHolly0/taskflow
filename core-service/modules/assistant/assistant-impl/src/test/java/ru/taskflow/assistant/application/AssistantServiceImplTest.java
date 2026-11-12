package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.DeclineReason;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.exception.ProposalNotFoundException;
import ru.taskflow.assistant.api.exception.ProposalNotPendingException;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.assistant.infrastructure.persistence.ProposalActionJpaEntity;
import ru.taskflow.assistant.infrastructure.persistence.ProposalFactory;
import ru.taskflow.assistant.infrastructure.persistence.ProposalJpaEntity;
import ru.taskflow.assistant.infrastructure.persistence.ProposalMapper;
import ru.taskflow.assistant.infrastructure.persistence.ProposalRepository;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.user.api.UserService;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantServiceImplTest {

    @Mock
    private AgentLoop agentLoop;
    @Mock
    private NlpGatewayService nlpGatewayService;
    @Mock
    private ProposalFactory proposalFactory;
    @Mock
    private ProposalMapper proposalMapper;
    @Mock
    private ProposalRepository proposalRepository;
    @Mock
    private ProposalApplier proposalApplier;
    @Mock
    private UserService userService;
    @Mock
    private TaskService taskService;
    @Mock
    private AuditService auditService;
    @Mock
    private AssistantRequestStore requestStore;
    @Mock
    private ProposalApplyTransaction applyTransaction;

    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-12T10:00:00+03:00");
    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.ofHours(3));
    private final ZoneId zone = ZoneId.of("Europe/Moscow");
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AssistantServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AssistantServiceImpl(agentLoop, nlpGatewayService, proposalFactory, proposalMapper,
                proposalRepository, proposalApplier, userService, taskService, auditService, clock, objectMapper, requestStore, applyTransaction,new AutomaticActionPolicy(),org.mockito.Mockito.mock(ru.taskflow.task.api.TaskOperationService.class));
        org.mockito.Mockito.lenient().when(applyTransaction.execute(any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get());
    }

    private AgentOutcome emptyWindowOutcome(List<ru.taskflow.assistant.api.dto.ProposedAction> actions,
                                             String clarification, String assistantText, boolean llmFailed) {
        TaskContextWindow window = new TaskContextWindow("", java.util.Map.of(), java.util.Map.of());
        return new AgentOutcome(actions, List.of(), clarification, List.of(), assistantText, window, 1, llmFailed);
    }

    private AgentOutcome emptyWindowOutcomeWithTokens(int inputTokens, int outputTokens) {
        TaskContextWindow window = new TaskContextWindow("", java.util.Map.of(), java.util.Map.of());
        return new AgentOutcome(List.of(), List.of(), null, List.of(), null, window, 1, false, false, null,
                inputTokens, outputTokens);
    }

    private AgentOutcome declinedOutcome(DeclineReason reason, String answer, int inputTokens, int outputTokens) {
        TaskContextWindow window = new TaskContextWindow("", java.util.Map.of(), java.util.Map.of());
        return new AgentOutcome(List.of(), List.of(), null, List.of(), answer, window, 1, false, false, null,
                inputTokens, outputTokens, 0, 0, 0, reason);
    }

    private ProposalActionJpaEntity action(int ordinal, boolean accepted) {
        ProposalActionJpaEntity action = new ProposalActionJpaEntity();
        action.setOrdinal(ordinal);
        action.setType("COMPLETE");
        action.setSummary("действие " + ordinal);
        action.setAccepted(accepted);
        return action;
    }

    private ProposalJpaEntity proposal(ProposalStatus status, OffsetDateTime expiresAt, ProposalActionJpaEntity... actions) {
        ProposalJpaEntity entity = new ProposalJpaEntity();
        entity.setUserId(userId);
        entity.setStatus(status.name());
        entity.setExpiresAt(expiresAt);
        for (ProposalActionJpaEntity action : actions) {
            entity.addAction(action);
        }
        return entity;
    }

    @Test
    void handleText_savesPendingProposal() {
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(
                List.of(new ru.taskflow.assistant.api.dto.ProposedAction(1, ru.taskflow.assistant.api.AssistantActionType.COMPLETE,
                        UUID.randomUUID(), java.util.Map.of(), "закрыть задачу", true)),
                null, null, false);
        when(agentLoop.run(userId, "закрой молоко", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);

        ProposalJpaEntity entity = new ProposalJpaEntity();
        when(proposalFactory.from(eq(userId), eq("закрой молоко"), eq(AssistantChannel.TELEGRAM), eq("TEXT"), eq(outcome)))
                .thenReturn(entity);
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Proposal result = service.handleText(userId, "закрой молоко", AssistantChannel.TELEGRAM);

        assertThat(result).isEqualTo(expectedDto);
        verify(taskService, never()).createQuick(any(), any());
    }

    @Test
    void handleText_savesRawTextTaskWhenLlmFailed() {
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(List.of(), null, null, true);
        when(agentLoop.run(userId, "хм", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse());

        Proposal result = service.handleText(userId, "хм", AssistantChannel.TELEGRAM);

        ArgumentCaptor<CreateTaskRequest> captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskService).createQuick(eq(userId), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("хм");
        assertThat(captor.getValue().source()).isEqualTo(TaskSource.ASSISTANT_BOT_TEXT_DEGRADED);
        verify(proposalRepository, never()).save(any());
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(result.sourceText()).isEqualTo("хм");
        // обращения к модели не было (llmFailed) — расход честно нулевой
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }

    @Test
    void handleText_doesNotCreateTaskWhenModelRespondedWithNothing() {
        // «спасибо» — модель ответила (токены реальные, llmFailed=false), но не
        // предложила ни действий, ни уточнения: измеренный результат, не сбой
        // (блок А) — не должна становиться задачей «спасибо».
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcomeWithTokens(1500, 200);
        when(agentLoop.run(userId, "спасибо", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);

        Proposal result = service.handleText(userId, "спасибо", AssistantChannel.TELEGRAM);

        verify(taskService, never()).createQuick(any(), any());
        verify(proposalRepository, never()).save(any());
        // recordDegradedCreation не вызывается — счётчик деградаций не растёт там,
        // где модель ответила
        verify(auditService, never()).record(any(), any(), any(), any());
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(result.actions()).isEmpty();
        // расход не нулевой — это отличает осознанный отказ действовать от
        // недоступности инфраструктуры (llmFailed, тест ниже)
        assertThat(result.inputTokens()).isEqualTo(1500);
        assertThat(result.outputTokens()).isEqualTo(200);
    }

    // --- Блок Б: no_action ---

    @Test
    void handleText_declinedQuestionDoesNotCreateTaskAndCarriesAnswer() {
        // «покажи задачи на завтра» — модель явно отказалась действием и
        // ответила текстом вместо того, чтобы становиться задачей (Б1/Б3).
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = declinedOutcome(DeclineReason.QUESTION, "На завтра задач нет.", 1600, 40);
        when(agentLoop.run(userId, "покажи задачи на завтра", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);

        Proposal result = service.handleText(userId, "покажи задачи на завтра", AssistantChannel.TELEGRAM);

        verify(taskService, never()).createQuick(any(), any());
        verify(auditService, never()).record(any(), any(), any(), any());
        verify(proposalRepository, never()).save(any());
        assertThat(result.status()).isEqualTo(ProposalStatus.DECLINED);
        assertThat(result.actions()).isEmpty();
        assertThat(result.clarification()).isEqualTo("На завтра задач нет.");
        assertThat(result.inputTokens()).isEqualTo(1600);
        assertThat(result.outputTokens()).isEqualTo(40);
    }

    @Test
    void handleText_declinedChitchatDoesNotCreateTask() {
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = declinedOutcome(DeclineReason.CHITCHAT, "Пожалуйста!", 1550, 20);
        when(agentLoop.run(userId, "спасибо", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);

        Proposal result = service.handleText(userId, "спасибо", AssistantChannel.TELEGRAM);

        verify(taskService, never()).createQuick(any(), any());
        assertThat(result.status()).isEqualTo(ProposalStatus.DECLINED);
        assertThat(result.clarification()).isEqualTo("Пожалуйста!");
    }

    // Пункт 2: напоминание на прошедший момент не создаёт задачу вместо себя.
    @Test
    void handleText_declinedPastReminderDoesNotCreateTask() {
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = declinedOutcome(DeclineReason.PAST, "Это время уже прошло.", 1700, 35);
        when(agentLoop.run(userId, "напомни про курсовую вчера в 10 утра", zone, AssistantEntryPoint.CHAT))
                .thenReturn(outcome);

        Proposal result = service.handleText(userId, "напомни про курсовую вчера в 10 утра", AssistantChannel.TELEGRAM);

        verify(taskService, never()).createQuick(any(), any());
        assertThat(result.status()).isEqualTo(ProposalStatus.DECLINED);
        assertThat(result.declineReason()).isEqualTo(DeclineReason.PAST);
        assertThat(result.clarification()).isEqualTo("Это время уже прошло.");
    }

    @Test
    void handleText_declineDoesNotInterceptOrdinaryCommand() {
        // Обычная команда с действиями по-прежнему идёт через сохранение
        // предложения — no_action её не перехватывает.
        when(userService.getTimezone(userId)).thenReturn(zone);
        var action = new ru.taskflow.assistant.api.dto.ProposedAction(1, ru.taskflow.assistant.api.AssistantActionType.COMPLETE,
                UUID.randomUUID(), java.util.Map.of(), "закрыть задачу", true);
        AgentOutcome outcome = emptyWindowOutcome(List.of(action), null, null, false);
        when(agentLoop.run(userId, "закрой молоко", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);

        ProposalJpaEntity entity = new ProposalJpaEntity();
        when(proposalFactory.from(eq(userId), eq("закрой молоко"), eq(AssistantChannel.TELEGRAM), eq("TEXT"), eq(outcome)))
                .thenReturn(entity);
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Proposal result = service.handleText(userId, "закрой молоко", AssistantChannel.TELEGRAM);

        assertThat(result).isEqualTo(expectedDto);
        verify(taskService, never()).createQuick(any(), any());
    }

    // Отказ отличим в данных от несостоявшегося обращения (llmFailed) и от
    // молчания модели (silentModelResponse, isEmpty без declineReason) — три
    // разных статуса/поля, не эвристика по тексту.
    @Test
    void handleText_declineIsDistinguishableFromLlmFailedAndFromSilentResponse() {
        when(userService.getTimezone(userId)).thenReturn(zone);

        when(agentLoop.run(userId, "отказ", zone, AssistantEntryPoint.CHAT))
                .thenReturn(declinedOutcome(DeclineReason.UNCLEAR, "Не понял, уточните.", 1500, 30));
        Proposal declined = service.handleText(userId, "отказ", AssistantChannel.TELEGRAM);

        when(agentLoop.run(userId, "молчание", zone, AssistantEntryPoint.CHAT))
                .thenReturn(emptyWindowOutcomeWithTokens(1500, 30));
        Proposal silent = service.handleText(userId, "молчание", AssistantChannel.TELEGRAM);

        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse());
        when(agentLoop.run(userId, "сбой", zone, AssistantEntryPoint.CHAT))
                .thenReturn(emptyWindowOutcome(List.of(), null, null, true));
        Proposal failed = service.handleText(userId, "сбой", AssistantChannel.TELEGRAM);

        // declined: собственный статус и declineReason.
        assertThat(declined.status()).isEqualTo(ProposalStatus.DECLINED);
        assertThat(declined.declineReason()).isEqualTo(DeclineReason.UNCLEAR);
        // silent: тот же FAILED, что и настоящий сбой, но без declineReason
        // и с ненулевым расходом — по нему отличимо от llmFailed.
        assertThat(silent.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(silent.declineReason()).isNull();
        assertThat(silent.inputTokens()).isEqualTo(1500);
        // llmFailed: FAILED и нулевой расход — обращения не было вовсе.
        assertThat(failed.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(failed.declineReason()).isNull();
        assertThat(failed.inputTokens()).isZero();
    }

    @Test
    void handleText_truncatesLongTextToColumnLimit() {
        String longText = "а".repeat(600);
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(List.of(), null, null, true);
        when(agentLoop.run(userId, longText, zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse());

        service.handleText(userId, longText, AssistantChannel.TELEGRAM);

        ArgumentCaptor<CreateTaskRequest> captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskService).createQuick(eq(userId), captor.capture());
        assertThat(captor.getValue().title()).hasSize(512);
        assertThat(captor.getValue().title()).isEqualTo(longText.substring(0, 512));
    }

    @Test
    void findById_rejectsForeignUser() {
        UUID proposalId = UUID.randomUUID();
        when(proposalRepository.findByIdAndUserId(proposalId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(userId, proposalId))
                .isInstanceOf(ProposalNotFoundException.class);
    }

    @Test
    void findLatestPending_returnsMappedProposal() {
        ProposalJpaEntity entity = new ProposalJpaEntity();
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "текст", null, List.of(), now, now.plusHours(24));
        when(proposalRepository.findLatestPending(eq(userId), eq(now), any()))
                .thenReturn(List.of(entity));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Optional<Proposal> result = service.findLatestPending(userId);

        assertThat(result).contains(expectedDto);
    }

    @Test
    void findLatestPending_returnsEmptyWhenNone() {
        when(proposalRepository.findLatestPending(eq(userId), eq(now), any()))
                .thenReturn(List.of());

        Optional<Proposal> result = service.findLatestPending(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void selectAlternative_acceptsChosenAndRejectsOthers() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity first = action(1, true);
        ProposalActionJpaEntity second = action(2, false);
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), first, second);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "текст", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Proposal result = service.selectAlternative(userId, proposalId, 2);

        assertThat(result).isEqualTo(expectedDto);
        assertThat(first.isAccepted()).isFalse();
        assertThat(second.isAccepted()).isTrue();
    }

    @Test
    void selectAlternative_rejectsNonPending() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.APPLIED, now.plusHours(24), action(1, true));
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.selectAlternative(userId, proposalId, 1))
                .isInstanceOf(ProposalNotPendingException.class);
    }

    @Test
    void setActionAccepted_rejectsNonPending() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.APPLIED, now.plusHours(24), action(1, true));
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.setActionAccepted(userId, proposalId, 1, false))
                .isInstanceOf(ProposalNotPendingException.class);
    }

    // --- Блок Г2: правка напоминания у отдельного действия предложения ---

    @Test
    void updateActionReminder_setsReminderOnCreateAction() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity createAction = action(1, true);
        createAction.setType("CREATE");
        createAction.setPayload("{\"title\":\"позвонить маме\"}");
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), createAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "текст", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        OffsetDateTime reminderAt = now.plusHours(2);
        Proposal result = service.updateActionReminder(userId, proposalId, 1, reminderAt);

        assertThat(result).isEqualTo(expectedDto);
        assertThat(createAction.getPayload()).contains("\"reminder_at\":\"" + reminderAt + "\"");
        assertThat(createAction.getPayload()).doesNotContain("no_reminder_needed");
    }

    @Test
    void updateActionReminder_nullClearsReminderAndMarksItAsDecidedNotForgotten() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity createAction = action(1, true);
        createAction.setType("CREATE");
        createAction.setPayload("{\"title\":\"позвонить маме\",\"reminder_at\":\"2026-08-12T12:00:00+03:00\"}");
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), createAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));
        when(proposalRepository.save(entity)).thenReturn(entity);
        when(proposalMapper.toDto(entity)).thenReturn(
                new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                        "текст", null, List.of(), now, now.plusHours(24)));

        service.updateActionReminder(userId, proposalId, 1, null);

        assertThat(createAction.getPayload()).doesNotContain("reminder_at");
        assertThat(createAction.getPayload()).contains("\"no_reminder_needed\":true");
    }

    @Test
    void updateActionReminder_rejectsPastTime() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity createAction = action(1, true);
        createAction.setType("CREATE");
        createAction.setPayload("{\"title\":\"позвонить маме\"}");
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), createAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateActionReminder(userId, proposalId, 1, now.minusMinutes(1)))
                .isInstanceOf(ValidationException.class);
        verify(proposalRepository, never()).save(any());
    }

    @Test
    void updateActionReminder_rejectsActionTypeWithoutReminderMeaning() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity completeAction = action(1, true);
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), completeAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateActionReminder(userId, proposalId, 1, now.plusHours(2)))
                .isInstanceOf(ValidationException.class);
        verify(proposalRepository, never()).save(any());
    }

    @Test
    void updateActionReminder_rejectsNonPending() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity createAction = action(1, true);
        createAction.setType("CREATE");
        createAction.setPayload("{\"title\":\"позвонить маме\"}");
        ProposalJpaEntity entity = proposal(ProposalStatus.APPLIED, now.plusHours(24), createAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateActionReminder(userId, proposalId, 1, now.plusHours(2)))
                .isInstanceOf(ProposalNotPendingException.class);
    }

    // --- Блок В2: правка дня исполнения у отдельного действия предложения ---

    @Test
    void updateActionPlannedDate_setsPlannedDateOnCreateAction() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity createAction = action(1, true);
        createAction.setType("CREATE");
        createAction.setPayload("{\"title\":\"разобрать шкаф\"}");
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), createAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "текст", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        OffsetDateTime plannedDate = now.plusDays(2);
        Proposal result = service.updateActionPlannedDate(userId, proposalId, 1, plannedDate);

        assertThat(result).isEqualTo(expectedDto);
        assertThat(createAction.getPayload()).contains("\"planned_date\":\"" + plannedDate + "\"");
        assertThat(createAction.getPayload()).doesNotContain("no_planned_date_needed");
    }

    @Test
    void updateActionPlannedDate_nullClearsPlannedDateAndMarksItAsDecidedNotForgotten() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity createAction = action(1, true);
        createAction.setType("CREATE");
        createAction.setPayload("{\"title\":\"разобрать шкаф\",\"planned_date\":\"2026-08-15T00:00:00+03:00\"}");
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), createAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));
        when(proposalRepository.save(entity)).thenReturn(entity);
        when(proposalMapper.toDto(entity)).thenReturn(
                new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                        "текст", null, List.of(), now, now.plusHours(24)));

        service.updateActionPlannedDate(userId, proposalId, 1, null);

        assertThat(createAction.getPayload()).doesNotContain("\"planned_date\":");
        assertThat(createAction.getPayload()).contains("\"no_planned_date_needed\":true");
    }

    @Test
    void updateActionPlannedDate_rejectsActionTypeWithoutPlannedDateMeaning() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity completeAction = action(1, true);
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), completeAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateActionPlannedDate(userId, proposalId, 1, now.plusDays(1)))
                .isInstanceOf(ValidationException.class);
        verify(proposalRepository, never()).save(any());
    }

    @Test
    void updateActionPlannedDate_rejectsNonPending() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity createAction = action(1, true);
        createAction.setType("CREATE");
        createAction.setPayload("{\"title\":\"разобрать шкаф\"}");
        ProposalJpaEntity entity = proposal(ProposalStatus.APPLIED, now.plusHours(24), createAction);
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateActionPlannedDate(userId, proposalId, 1, now.plusDays(1)))
                .isInstanceOf(ProposalNotPendingException.class);
    }

    @Test
    void apply_rejectsExpiredProposal() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.minusHours(1), action(1, true));
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        ApplyResult result = service.apply(userId, proposalId);

        assertThat(result.status()).isEqualTo(ProposalStatus.EXPIRED);
        assertThat(result.appliedCount()).isEqualTo(0);
        assertThat(entity.getStatus()).isEqualTo(ProposalStatus.EXPIRED.name());
        verify(proposalApplier, never()).apply(any(), any());
        verify(proposalRepository).save(entity);
    }

    @Test
    void applyReturnsPreviouslyCommittedResult() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.APPLIED, now.plusHours(24), action(1, true));
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));
        when(proposalMapper.toDto(entity)).thenReturn(new Proposal(proposalId, "CODE1234", userId,
                ProposalStatus.APPLIED, "закрой задачу", null,
                List.of(new ru.taskflow.assistant.api.dto.ProposedAction(1,
                        ru.taskflow.assistant.api.AssistantActionType.COMPLETE, null,
                        java.util.Map.of(), "действие 1", true)), now, now.plusHours(24)));

        assertThat(service.apply(userId, proposalId).status()).isEqualTo(ProposalStatus.APPLIED);
        verify(proposalApplier, never()).apply(any(), any());
    }

    @Test
    void reject_setsRejectedStatus() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), action(1, true));
        when(proposalRepository.findForUpdate(proposalId, userId)).thenReturn(Optional.of(entity));

        service.reject(userId, proposalId);

        assertThat(entity.getStatus()).isEqualTo(ProposalStatus.REJECTED.name());
        assertThat(entity.getResolvedAt()).isEqualTo(now);
        verify(proposalRepository).save(entity);
    }

    @Test
    void handleVoice_runsLoopOnTranscribedText() {
        byte[] audio = {1, 2, 3};
        when(nlpGatewayService.transcribe(audio)).thenReturn("закрой молоко");
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(
                List.of(new ru.taskflow.assistant.api.dto.ProposedAction(1, ru.taskflow.assistant.api.AssistantActionType.COMPLETE,
                        UUID.randomUUID(), java.util.Map.of(), "закрыть задачу", true)),
                null, null, false);
        when(agentLoop.run(userId, "закрой молоко", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);

        ProposalJpaEntity entity = new ProposalJpaEntity();
        when(proposalFactory.from(eq(userId), eq("закрой молоко"), eq(AssistantChannel.TELEGRAM), eq("VOICE"), eq(outcome)))
                .thenReturn(entity);
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Proposal result = service.handleVoice(userId, audio, AssistantChannel.TELEGRAM);

        assertThat(result).isEqualTo(expectedDto);
        verify(taskService, never()).createQuick(any(), any());
    }

    @Test
    void handleVoice_savesRawTaskWhenTranscriptionFails() {
        byte[] audio = {1, 2, 3};
        when(nlpGatewayService.transcribe(audio)).thenReturn(null);
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse());

        Proposal result = service.handleVoice(userId, audio, AssistantChannel.TELEGRAM);

        ArgumentCaptor<CreateTaskRequest> captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskService).createQuick(eq(userId), captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(TaskSource.ASSISTANT_BOT_VOICE_DEGRADED);
        verify(agentLoop, never()).run(any(), any(), any(), any());
        verify(proposalRepository, never()).save(any());
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        // обращения к модели не было вовсе — речь не распозналась до запуска AgentLoop
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
        assertThat(result.totalLatencyMs()).isZero();
        assertThat(result.modelPasses()).isZero();
    }

    @Test
    void transferOwnership_delegatesToRepository() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(proposalRepository.reassignOwner(from, to)).thenReturn(3);

        int result = service.transferOwnership(from, to);

        assertThat(result).isEqualTo(3);
        verify(proposalRepository).reassignOwner(from, to);
    }

    private TaskResponse taskResponse() {
        return new TaskResponse(UUID.randomUUID(), "х", null, null, null, null, null, null, null, null,
                List.of(), now, now, null, List.of());
    }
}
