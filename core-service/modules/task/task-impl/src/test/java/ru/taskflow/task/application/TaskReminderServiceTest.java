package ru.taskflow.task.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.exception.ReminderNotFoundException;
import ru.taskflow.task.infrastructure.persistence.ReminderJpaEntity;
import ru.taskflow.task.infrastructure.persistence.ReminderRepository;
import ru.taskflow.task.infrastructure.persistence.ReminderStatus;
import ru.taskflow.task.infrastructure.persistence.TaskJpaEntity;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReminderServiceTest {

    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserService userService;
    @Mock
    private ru.taskflow.task.infrastructure.persistence.TaskRepository taskRepository;

    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-26T12:00:00+03:00");
    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.ofHours(3));

    private TaskReminderService service;

    @BeforeEach
    void setUp() {
        service = new TaskReminderService(reminderRepository, notificationService, userService, clock, taskRepository);
    }

    private TaskJpaEntity task(TaskPriority priority, OffsetDateTime deadline) {
        var task = new TaskJpaEntity();
        task.setId(UUID.randomUUID());
        task.setUserId(userId);
        task.setTitle("проверить духовку");
        task.setPriority(priority);
        task.setDeadline(deadline);
        return task;
    }

    private UserSettingsDto settings(int defaultReminderMinutes, boolean urgentExtraReminder) {
        return new UserSettingsDto(true, true, true, true, defaultReminderMinutes, urgentExtraReminder,
                "groq", null, "SILENCE", "SILENCE", "Europe/Moscow", "user");
    }

    // --- А4 ---

    @Test
    void planForDeadline_computedTimeInPast_deadlineInFuture_firesAtDeadline() {
        // «напомни через 30 минут проверить духовку» при настройке «за час»:
        // расчётное время (дедлайн минус час) уже прошло, сам срок — ещё нет.
        OffsetDateTime deadline = now.plusMinutes(30);
        var task = task(TaskPriority.MEDIUM, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, true));

        service.planForDeadline(userId, task);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().getFireAt()).isEqualTo(deadline);
        assertThat(captor.getValue().getStatus()).isEqualTo(ReminderStatus.PENDING);
        verify(notificationService).scheduleReminder(userId, task.getId(), null, task.getTitle(), deadline, deadline);
    }

    @Test
    void planForDeadline_computedTimeAndDeadlineBothInPast_doesNotSchedule() {
        OffsetDateTime deadline = now.minusMinutes(5);
        var task = task(TaskPriority.MEDIUM, deadline);

        service.planForDeadline(userId, task);

        verify(reminderRepository, never()).save(any());
        verify(notificationService, never()).scheduleReminder(any(), any(), any(), any(), any(), any());
        verify(userService, never()).getSettings(any());
    }

    @Test
    void planForDeadline_noDeadline_doesNotSchedule() {
        var task = task(TaskPriority.MEDIUM, null);

        service.planForDeadline(userId, task);

        verify(reminderRepository, never()).save(any());
        verify(notificationService, never()).scheduleReminder(any(), any(), any(), any(), any(), any());
    }

    // --- А6 ---

    @Test
    void planForDeadline_urgentAndExtraReminderEnabled_schedulesSecondReminder() {
        OffsetDateTime deadline = now.plusDays(1);
        var task = task(TaskPriority.URGENT, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, true));

        service.planForDeadline(userId, task);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ReminderJpaEntity::getFireAt)
                .containsExactlyInAnyOrder(deadline.minusMinutes(60), deadline.minusMinutes(15));
        verify(notificationService, times(2)).scheduleReminder(eq(userId), eq(task.getId()), any(), eq(task.getTitle()), any(), eq(deadline));
    }

    @Test
    void planForDeadline_urgentButExtraReminderSettingOff_schedulesOnlyDefaultReminder() {
        OffsetDateTime deadline = now.plusDays(1);
        var task = task(TaskPriority.URGENT, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, false));

        service.planForDeadline(userId, task);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getFireAt()).isEqualTo(deadline.minusMinutes(60));
    }

    @Test
    void planForDeadline_notUrgent_schedulesOnlyDefaultReminderEvenWithExtraReminderEnabled() {
        OffsetDateTime deadline = now.plusDays(1);
        var task = task(TaskPriority.MEDIUM, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, true));

        service.planForDeadline(userId, task);

        verify(reminderRepository, times(1)).save(any());
    }

    // --- Б2 ---

    @Test
    void createStandaloneReminder_taskWithoutDeadline_schedulesReminderAndLeavesDeadlineNull() {
        var task = task(TaskPriority.MEDIUM, null);
        OffsetDateTime fireAt = now.plusDays(1).withHour(9).withMinute(0);

        service.createStandaloneReminder(userId, task, fireAt);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().getFireAt()).isEqualTo(fireAt);
        assertThat(captor.getValue().getTask()).isSameAs(task);
        verify(notificationService).scheduleReminder(userId, task.getId(), null, task.getTitle(), fireAt, null);
        assertThat(task.getDeadline()).isNull();
    }

    // --- отмена (Б4: своя таблица, задачу не трогает) ---

    @Test
    void cancelForTask_cancelsPendingRemindersAndScheduledNotifications() {
        UUID taskId = UUID.randomUUID();

        service.cancelForTask(taskId);

        verify(reminderRepository).cancelPendingByTaskId(taskId);
        verify(notificationService).cancelTaskNotifications(taskId);
    }

    // --- А2: снятие одного напоминания ---

    @Test
    void cancelReminder_cancelsOnlyThatReminderAndItsOwnNotifications() {
        UUID taskId = UUID.randomUUID();
        UUID reminderId = UUID.randomUUID();
        var reminder = new ReminderJpaEntity();
        reminder.setStatus(ReminderStatus.PENDING);
        when(reminderRepository.findForUpdate(reminderId, taskId)).thenReturn(Optional.of(reminder));

        service.cancelReminder(taskId, reminderId);

        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
        verify(reminderRepository).save(reminder);
        // Адресуется по id напоминания, а не задачи — соседние напоминания
        // той же задачи (и их уведомления) этот вызов не трогает.
        verify(notificationService).cancelReminderNotifications(reminderId);
        verify(notificationService, never()).cancelTaskNotifications(any());
        verify(reminderRepository, never()).cancelPendingByTaskId(any());
    }

    @Test
    void cancelReminder_throwsNotFound_whenReminderDoesNotBelongToTask() {
        UUID taskId = UUID.randomUUID();
        UUID reminderId = UUID.randomUUID();
        when(reminderRepository.findForUpdate(reminderId, taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelReminder(taskId, reminderId))
                .isInstanceOf(ReminderNotFoundException.class);
        verify(notificationService, never()).cancelReminderNotifications(any());
    }

    // --- Блок Б: настойчивость включается только для помеченной задачи ---

    private TaskJpaEntity persistentTask(TaskPriority priority, OffsetDateTime deadline) {
        var task = task(priority, deadline);
        task.setPersistentReminder(true);
        return task;
    }

    @Test
    void createStandaloneReminder_persistentTask_startsChainAtStepZero() {
        var task = persistentTask(TaskPriority.MEDIUM, null);
        OffsetDateTime fireAt = now.plusHours(2);

        service.createStandaloneReminder(userId, task, fireAt);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().getChainStep()).isZero();
    }

    @Test
    void createStandaloneReminder_ordinaryTask_doesNotStartChain() {
        var task = task(TaskPriority.MEDIUM, null);
        OffsetDateTime fireAt = now.plusHours(2);

        service.createStandaloneReminder(userId, task, fireAt);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().getChainStep()).isNull();
    }

    @Test
    void planForDeadline_persistentUrgentTask_onlyMainReminderStartsChain_notTheExtraOne() {
        OffsetDateTime deadline = now.plusDays(1);
        var task = persistentTask(TaskPriority.URGENT, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, true));

        service.planForDeadline(userId, task);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository, times(2)).save(captor.capture());
        long chainStarts = captor.getAllValues().stream().filter(r -> r.getChainStep() != null).count();
        assertThat(chainStarts).isEqualTo(1);
    }

    // --- Б2: точка решения — отложить ---

    @Test
    void snoozeReminder_marksSnoozedAndSchedulesNextAtChosenTime_keepingSameChainStep() {
        UUID taskId = UUID.randomUUID();
        UUID reminderId = UUID.randomUUID();
        var task = persistentTask(TaskPriority.MEDIUM, null);
        var reminder = new ReminderJpaEntity();
        reminder.setTask(task);
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setChainStep(1);
        when(reminderRepository.findForUpdate(reminderId, taskId)).thenReturn(Optional.of(reminder));
        OffsetDateTime until = now.plusMinutes(30);

        service.snoozeReminder(taskId, reminderId, until);

        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SNOOZED);
        verify(notificationService).cancelReminderNotifications(reminderId);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        // save(reminder) для самого отложенного + save(next) для нового — 2 вызова.
        verify(reminderRepository, times(2)).save(captor.capture());
        var next = captor.getAllValues().get(1);
        assertThat(next.getFireAt()).isEqualTo(until);
        assertThat(next.getChainStep()).isEqualTo(1);
    }

    // --- Б1/Б4: снятие флага гасит только цепочку ---

    @Test
    void cancelPersistentChain_cancelsOnlyChainTaggedPendingReminders_leavesOrdinaryOnesAlone() {
        UUID taskId = UUID.randomUUID();
        var chainReminder = new ReminderJpaEntity();
        chainReminder.setId(UUID.randomUUID());
        chainReminder.setStatus(ReminderStatus.PENDING);
        chainReminder.setChainStep(2);
        var ordinaryReminder = new ReminderJpaEntity();
        ordinaryReminder.setId(UUID.randomUUID());
        ordinaryReminder.setStatus(ReminderStatus.PENDING);
        ordinaryReminder.setChainStep(null);
        when(reminderRepository.findByTaskIdAndStatusOrderByFireAtAsc(taskId, ReminderStatus.PENDING))
                .thenReturn(List.of(chainReminder, ordinaryReminder));

        service.cancelPersistentChain(taskId);

        assertThat(chainReminder.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
        assertThat(ordinaryReminder.getStatus()).isEqualTo(ReminderStatus.PENDING);
        verify(reminderRepository, never()).save(ordinaryReminder);
        verify(notificationService).cancelReminderNotifications(chainReminder.getId());
        verify(notificationService, never()).cancelReminderNotifications(ordinaryReminder.getId());
    }

    // --- Б3: затухание — конечное число автоматических повторов, промежутки растут ---

    @Test
    void advancePersistentChains_dueReminder_dismissesItAndSchedulesNextWithGrowingGap() {
        var task = persistentTask(TaskPriority.MEDIUM, null);
        var due = new ReminderJpaEntity();
        due.setId(UUID.randomUUID());
        due.setTask(task);
        due.setStatus(ReminderStatus.PENDING);
        due.setChainStep(0);
        due.setFireAt(now.minusMinutes(1));
        pendingCandidate(due);

        service.advancePersistentChains(now);

        assertThat(due.getStatus()).isEqualTo(ReminderStatus.DISMISSED);
        verify(notificationService).cancelReminderNotifications(due.getId());

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository, times(2)).save(captor.capture());
        var next = captor.getAllValues().get(1);
        assertThat(next.getChainStep()).isEqualTo(1);
        assertThat(next.getFireAt()).isEqualTo(due.getFireAt().plusMinutes(15));
    }

    @Test
    void advancePersistentChains_lastStepDue_dismissesAndDoesNotScheduleFurther() {
        var task = persistentTask(TaskPriority.MEDIUM, null);
        var lastStep = new ReminderJpaEntity();
        lastStep.setId(UUID.randomUUID());
        lastStep.setTask(task);
        lastStep.setStatus(ReminderStatus.PENDING);
        lastStep.setChainStep(3); // после трёх автоматических шагов запас исчерпан
        lastStep.setFireAt(now.minusMinutes(1));
        pendingCandidate(lastStep);

        service.advancePersistentChains(now);

        assertThat(lastStep.getStatus()).isEqualTo(ReminderStatus.DISMISSED);
        verify(reminderRepository, times(1)).save(any());
        verify(notificationService, never()).scheduleReminder(any(), any(), any(), any(), any(), any());
    }
    private void pendingCandidate(ReminderJpaEntity reminder) {
        var task = reminder.getTask();
        var candidate = org.mockito.Mockito.mock(ReminderRepository.DueChainCandidate.class);
        when(candidate.getId()).thenReturn(reminder.getId());
        when(candidate.getTaskId()).thenReturn(task.getId());
        when(candidate.getUserId()).thenReturn(userId);
        when(reminderRepository.findDueChainCandidates(eq(now), any())).thenReturn(List.of(candidate));
        when(taskRepository.findForUpdate(task.getId(), userId)).thenReturn(Optional.of(task));
        when(reminderRepository.findForUpdate(reminder.getId(), task.getId())).thenReturn(Optional.of(reminder));
    }

}
