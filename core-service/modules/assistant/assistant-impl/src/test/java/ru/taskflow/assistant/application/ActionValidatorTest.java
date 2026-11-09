package ru.taskflow.assistant.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.exception.TaskNotFoundException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionValidatorTest {

    @Mock
    private TaskService taskService;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-27T12:00:00+03:00");
    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.ofHours(3));

    private ActionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ActionValidator(taskService, clock);
    }

    private TaskContextWindow windowWith(UUID id) {
        return new TaskContextWindow("T1 · купить молоко", Map.of("T1", id), Map.of("T1", "купить молоко"));
    }

    @Test
    void validatesPlannedDayAndRejectsItAfterDeadline() {
        var window = windowWith(taskId);
        assertThat(validator.validate(AssistantActionType.CREATE, Map.of("title", "Подготовить доклад",
                "planned_date", "2026-08-28T09:00:00+03:00"), window).valid()).isTrue();
        assertThat(validator.validate(AssistantActionType.CREATE, Map.of("title", "Подготовить доклад",
                "planned_date", "не дата"), window).valid()).isFalse();
        assertThat(validator.validate(AssistantActionType.CREATE, Map.of("title", "Подготовить доклад",
                "planned_date", "2026-08-29T09:00:00+03:00", "deadline", "2026-08-28T09:00:00+03:00"), window).valid()).isFalse();
    }

    @Test
    void validate_acceptsCreateWithTitle() {
        var result = validator.validate(AssistantActionType.CREATE,
                Map.of("title", "созвониться с Марком"), windowWith(taskId));

        assertThat(result.valid()).isTrue();
        assertThat(result.targetTaskId()).isNull();
    }

    @Test
    void validate_rejectsRecurrenceAsUnsupported() {
        var result = validator.validate(AssistantActionType.CREATE,
                Map.of("title", "Зарядка", "recurrence", "DAILY"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("повторяющиеся задачи");
    }

    @Test
    void validate_rejectsUpdateWithoutAnyField() {
        var result = validator.validate(AssistantActionType.UPDATE,
                Map.of("task_ref", "T1"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("нечего менять");
    }

    @Test
    void validate_keepsTargetTaskIdOnEmptyUpdateRejection() {
        // Ярлык разрешился, дальше не собралось — не то же самое, что
        // «неизвестный ярлык»: AgentLoop использует эту задачу как rejectedTarget.
        var result = validator.validate(AssistantActionType.UPDATE,
                Map.of("task_ref", "T1"), windowWith(taskId));

        assertThat(result.targetTaskId()).isEqualTo(taskId);
    }

    @Test
    void validate_keepsTargetTaskIdOnRescheduleWithoutDeadline() {
        var result = validator.validate(AssistantActionType.RESCHEDULE,
                Map.of("task_ref", "T1"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.targetTaskId()).isEqualTo(taskId);
    }

    @Test
    void validate_acceptsUpdateWithOneField() {
        var result = validator.validate(AssistantActionType.UPDATE,
                Map.of("task_ref", "T1", "priority", "HIGH"), windowWith(taskId));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validate_rejectsCreateWithoutTitle() {
        var result = validator.validate(AssistantActionType.CREATE,
                Map.of(), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("title");
    }

    @Test
    void validate_resolvesKnownRef() {
        var result = validator.validate(AssistantActionType.COMPLETE,
                Map.of("task_ref", "T1"), windowWith(taskId));

        assertThat(result.valid()).isTrue();
        assertThat(result.targetTaskId()).isEqualTo(taskId);
    }

    @Test
    void validate_rejectsUnknownRef() {
        var result = validator.validate(AssistantActionType.COMPLETE,
                Map.of("task_ref", "T99"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("T99");
    }

    @Test
    void validate_rejectsMissingRef() {
        var result = validator.validate(AssistantActionType.COMPLETE,
                Map.of(), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("task_ref");
    }

    @Test
    void validate_rejectsRescheduleWithoutDeadline() {
        var result = validator.validate(AssistantActionType.RESCHEDULE,
                Map.of("task_ref", "T1"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("new_deadline");
    }

    @Test
    void validate_rejectsUnparseableDeadline() {
        var result = validator.validate(AssistantActionType.RESCHEDULE,
                Map.of("task_ref", "T1", "new_deadline", "завтра вечером"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("срок");
    }

    @Test
    void validate_rejectsUnknownPriority() {
        var result = validator.validate(AssistantActionType.UPDATE,
                Map.of("task_ref", "T1", "priority", "ОЧЕНЬ_СРОЧНО"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("приоритет");
    }

    @Test
    void revalidateForApply_acceptsActiveTask() {
        when(taskService.findById(userId, taskId)).thenReturn(task(TaskStatus.TODO));

        var result = validator.revalidateForApply(userId, AssistantActionType.COMPLETE, taskId);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void revalidateForApply_rejectsAlreadyCompletedTask() {
        when(taskService.findById(userId, taskId)).thenReturn(task(TaskStatus.DONE));

        var result = validator.revalidateForApply(userId, AssistantActionType.COMPLETE, taskId);

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("уже");
    }

    @Test
    void revalidateForApply_rejectsMissingTask() {
        when(taskService.findById(userId, taskId)).thenThrow(new TaskNotFoundException(taskId));

        var result = validator.revalidateForApply(userId, AssistantActionType.COMPLETE, taskId);

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("не найдена");
    }

    // --- Блок В: remind ---

    @Test
    void validate_acceptsRemindWithFutureAbsoluteTime() {
        var result = validator.validate(AssistantActionType.REMIND,
                Map.of("task_ref", "T1", "reminder_at", now.plusHours(1).toString()), windowWith(taskId));

        assertThat(result.valid()).isTrue();
        assertThat(result.targetTaskId()).isEqualTo(taskId);
    }

    @Test
    void validate_rejectsRemindWithoutReminderAt() {
        var result = validator.validate(AssistantActionType.REMIND,
                Map.of("task_ref", "T1"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("reminder_at");
        assertThat(result.targetTaskId()).isEqualTo(taskId);
    }

    @Test
    void validate_rejectsRemindWithUnparseableTime() {
        var result = validator.validate(AssistantActionType.REMIND,
                Map.of("task_ref", "T1", "reminder_at", "завтра вечером"), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("время напоминания");
    }

    // Прошедшее время отклоняется (та же прикладная валидация, что и у
    // остальных действий) — точное условие для остального стенда.
    @Test
    void validate_rejectsRemindInThePast() {
        var result = validator.validate(AssistantActionType.REMIND,
                Map.of("task_ref", "T1", "reminder_at", now.minusMinutes(5).toString()), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("прошло");
    }

    @Test
    void validate_rejectsRemindForUnknownTask() {
        var result = validator.validate(AssistantActionType.REMIND,
                Map.of("task_ref", "T99", "reminder_at", now.plusHours(1).toString()), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("T99");
    }

    @Test
    void validate_acceptsCreateWithFutureReminderAt() {
        var result = validator.validate(AssistantActionType.CREATE,
                Map.of("title", "встреча с врачом", "deadline", now.plusHours(3).toString(),
                        "reminder_at", now.plusHours(2).toString()), windowWith(taskId));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validate_rejectsCreateWithPastReminderAt() {
        var result = validator.validate(AssistantActionType.CREATE,
                Map.of("title", "встреча с врачом", "reminder_at", now.minusMinutes(5).toString()), windowWith(taskId));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("прошло");
    }

    @Test
    void revalidateForApply_rejectsRemindOnCompletedTask() {
        when(taskService.findById(userId, taskId)).thenReturn(task(TaskStatus.DONE));

        var result = validator.revalidateForApply(userId, AssistantActionType.REMIND, taskId);

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("уже");
    }

    private TaskResponse task(TaskStatus status) {
        return new TaskResponse(
                taskId, "купить молоко", null, TaskPriority.MEDIUM, status, null,
                null, TaskSource.MANUAL, null, null,
                List.of(), OffsetDateTime.now(), OffsetDateTime.now(), null, List.of()
        );
    }
}
