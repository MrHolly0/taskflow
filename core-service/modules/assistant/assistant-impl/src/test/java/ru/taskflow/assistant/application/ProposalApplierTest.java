package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ActionOutcome;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.infrastructure.persistence.ProposalActionJpaEntity;
import ru.taskflow.assistant.infrastructure.persistence.ProposalJpaEntity;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProposalApplierTest {

    @Mock
    private ActionValidator actionValidator;

    @Mock
    private TaskService taskService;

    @Mock
    private AuditService auditService;

    @Mock
    private ProposalActionTransaction actionTransaction;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProposalApplier applier;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        applier = new ProposalApplier(actionValidator, taskService, auditService, objectMapper, actionTransaction);
        org.mockito.Mockito.lenient().when(actionTransaction.execute(any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<UUID>>getArgument(1).get());
    }

    private ProposalJpaEntity proposal(String sourceChannel, String inputKind, ProposalActionJpaEntity... actions) {
        ProposalJpaEntity entity = new ProposalJpaEntity();
        entity.setUserId(userId);
        entity.setSourceChannel(sourceChannel);
        entity.setInputKind(inputKind);
        entity.setStatus(ProposalStatus.PENDING.name());
        for (ProposalActionJpaEntity action : actions) {
            entity.addAction(action);
        }
        return entity;
    }

    private ProposalActionJpaEntity action(int ordinal, AssistantActionType type, UUID targetTaskId,
                                            String payload, boolean accepted) {
        ProposalActionJpaEntity action = new ProposalActionJpaEntity();
        action.setOrdinal(ordinal);
        action.setType(type.name());
        action.setTargetTaskId(targetTaskId);
        action.setPayload(payload);
        action.setSummary("действие " + ordinal);
        action.setAccepted(accepted);
        return action;
    }

    private TaskResponse taskResponse(UUID id) {
        return new TaskResponse(
                id, "задача", null, TaskPriority.MEDIUM, TaskStatus.TODO, null,
                null, TaskSource.MANUAL, null, null,
                List.of(), OffsetDateTime.now(), OffsetDateTime.now(), null, List.of()
        );
    }

    @Test
    void apply_appliesAllAccepted() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID createdId = UUID.randomUUID();

        ProposalActionJpaEntity a1 = action(0, AssistantActionType.CREATE, null, "{\"title\":\"новая\"}", true);
        ProposalActionJpaEntity a2 = action(1, AssistantActionType.COMPLETE, t1, null, true);
        ProposalActionJpaEntity a3 = action(2, AssistantActionType.CANCEL, t2, null, true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1, a2, a3);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.CREATE, null))
                .thenReturn(new ActionValidator.ValidationResult(true, null, null));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.CANCEL, t2))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t2));

        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse(createdId));

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.status()).isEqualTo(ProposalStatus.APPLIED);
        assertThat(result.appliedCount()).isEqualTo(3);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(entity.getStatus()).isEqualTo(ProposalStatus.APPLIED.name());
        assertThat(entity.getResolvedAt()).isNotNull();
        assertThat(a1.getAppliedTaskId()).isEqualTo(createdId);
        assertThat(a2.getAppliedTaskId()).isEqualTo(t1);
        assertThat(a3.getAppliedTaskId()).isEqualTo(t2);
        // Не только на сущности действия — интерфейсу после подтверждения
        // нужно сослаться на затронутую задачу через сам ApplyResult
        // (например, включить настойчивость только что созданной задаче).
        assertThat(result.outcomes()).extracting(ActionOutcome::taskId)
                .containsExactly(createdId, t1, t2);
    }

    @Test
    void resumedProposalSkipsAlreadyCommittedAction() {
        UUID taskId = UUID.randomUUID();
        var committed = action(0, AssistantActionType.CREATE, null, "{\"title\":\"новая\"}", true);
        committed.setAppliedTaskId(taskId);

        var result = applier.apply(userId, proposal("WEB", "TEXT", committed));

        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.outcomes().getFirst().taskId()).isEqualTo(taskId);
        org.mockito.Mockito.verifyNoInteractions(taskService, actionTransaction, actionValidator);
    }

    @Test
    void apply_skipsUnaccepted() {
        UUID t1 = UUID.randomUUID();
        ProposalActionJpaEntity accepted = action(0, AssistantActionType.COMPLETE, t1, null, true);
        ProposalActionJpaEntity rejected = action(1, AssistantActionType.CANCEL, UUID.randomUUID(), null, false);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", accepted, rejected);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(ProposalStatus.APPLIED);
        verify(actionValidator, never()).revalidateForApply(eq(userId), eq(AssistantActionType.CANCEL), any());
    }

    @Test
    void apply_recordsPartialFailure() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.COMPLETE, t1, null, true);
        ProposalActionJpaEntity a2 = action(1, AssistantActionType.COMPLETE, t2, null, true);
        ProposalActionJpaEntity a3 = action(2, AssistantActionType.COMPLETE, t3, null, true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1, a2, a3);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t2))
                .thenReturn(new ActionValidator.ValidationResult(false, "задача уже закрыта", null));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t3))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t3));

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.status()).isEqualTo(ProposalStatus.PARTIALLY_APPLIED);
        assertThat(result.appliedCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(a2.getApplyError()).isEqualTo("задача уже закрыта");
        assertThat(a1.getApplyError()).isNull();
        assertThat(a3.getApplyError()).isNull();
        verify(taskService, never()).complete(userId, t2);
        verify(taskService).complete(userId, t1);
        verify(taskService).complete(userId, t3);
    }

    @Test
    void apply_marksFailedWhenNothingApplied() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.COMPLETE, t1, null, true);
        ProposalActionJpaEntity a2 = action(1, AssistantActionType.COMPLETE, t2, null, true);
        ProposalActionJpaEntity a3 = action(2, AssistantActionType.COMPLETE, t3, null, true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1, a2, a3);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenReturn(new ActionValidator.ValidationResult(false, "задача не найдена или недоступна", null));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t2))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t2));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t3))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t3));

        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(taskService).complete(userId, t2);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(taskService).complete(userId, t3);

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(result.appliedCount()).isEqualTo(0);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(a1.getApplyError()).isEqualTo("задача не найдена или недоступна");
        assertThat(a2.getApplyError()).isNotNull();
        assertThat(a3.getApplyError()).isNotNull();
    }

    @Test
    void apply_survivesServiceException() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.COMPLETE, t1, null, true);
        ProposalActionJpaEntity a2 = action(1, AssistantActionType.COMPLETE, t2, null, true);
        ProposalActionJpaEntity a3 = action(2, AssistantActionType.COMPLETE, t3, null, true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1, a2, a3);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t2))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t2));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t3))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t3));

        org.mockito.Mockito.doNothing().when(taskService).complete(userId, t1);
        org.mockito.Mockito.doThrow(new RuntimeException("boom, database offline"))
                .when(taskService).complete(userId, t2);
        org.mockito.Mockito.doNothing().when(taskService).complete(userId, t3);

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.status()).isEqualTo(ProposalStatus.PARTIALLY_APPLIED);
        assertThat(result.appliedCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(a2.getApplyError()).isNotNull();
        assertThat(a2.getApplyError()).doesNotContain("boom");
        assertThat(a1.getApplyError()).isNull();
        assertThat(a3.getApplyError()).isNull();
        assertThat(a1.getAppliedTaskId()).isEqualTo(t1);
        assertThat(a3.getAppliedTaskId()).isEqualTo(t3);
        verify(taskService).complete(userId, t1);
        verify(taskService).complete(userId, t3);
    }

    @Test
    void apply_usesCreateQuickForCreate() {
        UUID createdId = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.CREATE, null, "{\"title\":\"новая задача\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.CREATE, null))
                .thenReturn(new ActionValidator.ValidationResult(true, null, null));
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse(createdId));

        applier.apply(userId, entity);

        verify(taskService).createQuick(eq(userId), any());
        verify(taskService, never()).create(any(), any());
    }

    @Test
    void apply_writesAudit() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.COMPLETE, t1, null, true);
        ProposalActionJpaEntity a2 = action(1, AssistantActionType.CANCEL, t2, null, true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1, a2);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.CANCEL, t2))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t2));

        applier.apply(userId, entity);

        verify(auditService, times(2)).record(eq(userId), any(), any(), any());
    }

    @Test
    void apply_setsAppliedTaskIdForCreate() {
        UUID createdId = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.CREATE, null, "{\"title\":\"новая задача\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.CREATE, null))
                .thenReturn(new ActionValidator.ValidationResult(true, null, null));
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse(createdId));

        applier.apply(userId, entity);

        assertThat(a1.getAppliedTaskId()).isEqualTo(createdId);
    }

    @Test
    void apply_survivesAuditFailure() {
        UUID t1 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.COMPLETE, t1, null, true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));
        org.mockito.Mockito.doThrow(new RuntimeException("audit down"))
                .when(auditService).record(eq(userId), eq(t1), any(), any());

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.status()).isEqualTo(ProposalStatus.APPLIED);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(a1.getApplyError()).isNull();
        assertThat(a1.getAppliedTaskId()).isEqualTo(t1);
        verify(taskService).complete(userId, t1);
    }

    @Test
    void apply_survivesUnexpectedRevalidationException() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.COMPLETE, t1, null, true);
        ProposalActionJpaEntity a2 = action(1, AssistantActionType.COMPLETE, t2, null, true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1, a2);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenThrow(new RuntimeException("база недоступна"));
        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t2))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t2));

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.status()).isEqualTo(ProposalStatus.PARTIALLY_APPLIED);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(a1.getApplyError()).isNotNull();
        assertThat(a1.getApplyError()).doesNotContain("база недоступна");
        verify(taskService).complete(userId, t2);
    }

    @Test
    void apply_derivesTaskSourceFromProposalChannel() {
        UUID createdId = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.CREATE, null, "{\"title\":\"новая\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "VOICE", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.CREATE, null))
                .thenReturn(new ActionValidator.ValidationResult(true, null, null));
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse(createdId));

        applier.apply(userId, entity);

        var captor = org.mockito.ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskService).createQuick(eq(userId), captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(TaskSource.ASSISTANT_BOT_VOICE);
    }

    @Test
    void apply_handlesEmptyAcceptedList() {
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.COMPLETE, UUID.randomUUID(), null, false);
        ProposalActionJpaEntity a2 = action(1, AssistantActionType.CANCEL, UUID.randomUUID(), null, false);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1, a2);

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.status()).isEqualTo(ProposalStatus.APPLIED);
        assertThat(result.appliedCount()).isEqualTo(0);
        assertThat(result.totalCount()).isEqualTo(0);
        assertThat(entity.getStatus()).isEqualTo(ProposalStatus.APPLIED.name());
    }

    @Test
    void apply_updatesGroupOnlyViaAssistant() {
        UUID t1 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.UPDATE, t1, "{\"group\":\"Работа\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.UPDATE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));

        ApplyResult result = applier.apply(userId, entity);

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateTaskRequest.class);
        verify(taskService).update(eq(userId), eq(t1), captor.capture());
        assertThat(captor.getValue().groupName()).isEqualTo("Работа");
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(a1.getApplyError()).isNull();
    }

    @Test
    void apply_rejectsRescheduleWithUnparseableDeadline() {
        UUID t1 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.RESCHEDULE, t1, "{\"new_deadline\":\"не дата\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.RESCHEDULE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.appliedCount()).isEqualTo(0);
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(a1.getApplyError()).isNotNull();
        verify(taskService, never()).update(any(), any(), any());
    }

    @Test
    void apply_rejectsUpdateWhenAllFieldsUnparseable() {
        UUID t1 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.UPDATE, t1, "{\"priority\":\"неведомый\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.UPDATE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.appliedCount()).isEqualTo(0);
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(a1.getApplyError()).isNotNull();
        verify(taskService, never()).update(any(), any(), any());
    }

    // --- Блок В: remind ---

    @Test
    void apply_dispatchesRemindToScheduleReminder() {
        UUID t1 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.REMIND, t1,
                "{\"reminder_at\":\"2026-08-13T09:00:00+03:00\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.REMIND, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));

        ApplyResult result = applier.apply(userId, entity);

        verify(taskService).scheduleReminder(userId, t1, OffsetDateTime.parse("2026-08-13T09:00:00+03:00"));
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(a1.getApplyError()).isNull();
        assertThat(a1.getAppliedTaskId()).isEqualTo(t1);
    }

    @Test
    void apply_rejectsRemindWithUnparseableTime() {
        UUID t1 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.REMIND, t1, "{\"reminder_at\":\"не время\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.REMIND, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));

        ApplyResult result = applier.apply(userId, entity);

        assertThat(result.appliedCount()).isEqualTo(0);
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        verify(taskService, never()).scheduleReminder(any(), any(), any());
    }

    // Срок задачи и время напоминания — разные вещи: create с reminder_at
    // ставит напоминание отдельным вызовом уже после создания задачи, а не
    // выводит его из deadline. Работает и без deadline вовсе.
    @Test
    void apply_createWithReminderAtSchedulesReminderAfterCreation() {
        UUID createdId = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.CREATE, null,
                "{\"title\":\"позвонить маме\",\"reminder_at\":\"2026-08-13T10:00:00+03:00\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.CREATE, null))
                .thenReturn(new ActionValidator.ValidationResult(true, null, null));
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse(createdId));

        applier.apply(userId, entity);

        verify(taskService).createQuick(eq(userId), any());
        verify(taskService).scheduleReminder(userId, createdId, OffsetDateTime.parse("2026-08-13T10:00:00+03:00"));
    }

    @Test
    void apply_createWithoutReminderAtDoesNotScheduleOne() {
        UUID createdId = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.CREATE, null, "{\"title\":\"обычная задача\"}", true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.CREATE, null))
                .thenReturn(new ActionValidator.ValidationResult(true, null, null));
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse(createdId));

        applier.apply(userId, entity);

        verify(taskService, never()).scheduleReminder(any(), any(), any());
    }

    @Test
    void apply_preservesDomainExceptionMessage() {
        UUID t1 = UUID.randomUUID();
        ProposalActionJpaEntity a1 = action(0, AssistantActionType.COMPLETE, t1, null, true);
        ProposalJpaEntity entity = proposal("TELEGRAM", "TEXT", a1);

        when(actionValidator.revalidateForApply(userId, AssistantActionType.COMPLETE, t1))
                .thenReturn(new ActionValidator.ValidationResult(true, null, t1));
        ru.taskflow.task.api.exception.TaskNotFoundException domainException =
                new ru.taskflow.task.api.exception.TaskNotFoundException(t1);
        org.mockito.Mockito.doThrow(domainException).when(taskService).complete(userId, t1);

        applier.apply(userId, entity);

        assertThat(a1.getApplyError()).isEqualTo(domainException.getMessage());
    }
}
