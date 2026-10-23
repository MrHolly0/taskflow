package ru.taskflow.task.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.task.api.exception.ReminderNotFoundException;
import ru.taskflow.task.infrastructure.persistence.ReminderJpaEntity;
import ru.taskflow.task.infrastructure.persistence.ReminderRepository;
import ru.taskflow.task.infrastructure.persistence.ReminderStatus;
import ru.taskflow.task.infrastructure.persistence.TaskJpaEntity;
import ru.taskflow.task.infrastructure.persistence.TaskRepository;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Планирует и отменяет напоминания через таблицу reminders — она источник
 * истины о том, сколько у задачи напоминаний и когда каждое сработает
 * (Б1), а не deadline задачи, вычисляемый заново при каждом обращении.
 * Срок задачи и время напоминания — разные вещи (обоснование в
 * исследование-напоминания.md): срок можно не указывать вовсе, а
 * напоминание всё равно назначить (Б2).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskReminderService {

    // Совпадает с подписью в настройках («Доп. за 15 мин для срочных») —
    // отдельного поля под этот отступ в UserSettingsDto нет, urgentExtraReminder
    // только включает/выключает саму идею дополнительного напоминания.
    private static final int URGENT_EXTRA_MINUTES = 15;

    // Настойчивость (Б3, затухание): три автоматических шага после исходного
    // напоминания, промежутки растут — 15 минут, час, четыре часа. Дальше
    // молчим: §1.5 предупреждает про внешнюю зависимость вместо привычки,
    // цель — чтобы человек справлялся, а не чтобы не мог без нас. Снятое
    // вручную «отложить» этот запас не тратит — считаются только шаги,
    // которые сработали без решения человека.
    private static final List<Duration> CHAIN_STEP_GAPS = List.of(
            Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(4)
    );

    private final ReminderRepository reminderRepository;
    private final NotificationService notificationService;
    private final UserService userService;
    private final Clock clock;
    private final TaskRepository taskRepository;

    /**
     * Обычное напоминание — за defaultReminderMinutes до срока. Если это
     * время уже прошло, а сам срок ещё нет — напоминаем в момент срока
     * (А4). Если прошёл и сам срок — не напоминаем вовсе. Для приоритета
     * URGENT при включённом urgentExtraReminder — ещё одно, за 15 минут до
     * срока (А6), если оно не совпало с обычным.
     */
    @Transactional
    public void planForDeadline(UUID userId, TaskJpaEntity task) {
        OffsetDateTime deadline = task.getDeadline();
        if (deadline == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (deadline.isBefore(now)) {
            log.debug("Дедлайн задачи {} уже прошёл — напоминание не планируется", task.getId());
            return;
        }

        UserSettingsDto settings = userService.getSettings(userId);
        OffsetDateTime fireAt = clampToDeadline(deadline.minusMinutes(settings.defaultReminderMinutes()), deadline, now);
        createReminder(userId, task, fireAt, deadline, chainStartOrNull(task));

        if (task.getPriority() == TaskPriority.URGENT && settings.urgentExtraReminder()) {
            OffsetDateTime urgentFireAt = clampToDeadline(deadline.minusMinutes(URGENT_EXTRA_MINUTES), deadline, now);
            if (!urgentFireAt.isEqual(fireAt)) {
                // Не отдельная вторая цепочка на той же задаче — только основное
                // напоминание запускает настойчивость (Б2).
                createReminder(userId, task, urgentFireAt, deadline, null);
            }
        }
    }

    /**
     * Б2: напоминание на конкретное время независимо от срока задачи —
     * deadline задачи здесь не читается и не меняется.
     */
    @Transactional
    public void createStandaloneReminder(UUID userId, TaskJpaEntity task, OffsetDateTime fireAt) {
        if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
            throw new ValidationException("Нельзя добавить напоминание для закрытой задачи");
        }
        createReminder(userId, task, fireAt, null, chainStartOrNull(task));
    }

    private Integer chainStartOrNull(TaskJpaEntity task) {
        return task.isPersistentReminder() ? 0 : null;
    }

    /**
     * Пользователь отложил конкретный повтор цепочки на выбранный срок (Б2) —
     * точка решения, а не автоматический шаг: запас автоматических повторов
     * (Б3) не тратится, chainStep у новой строки тот же, что у отложенной.
     */
    @Transactional
    public void snoozeReminder(UUID taskId, UUID reminderId, OffsetDateTime until) {
        var reminder = reminderRepository.findForUpdate(reminderId, taskId)
                .orElseThrow(() -> new ReminderNotFoundException(reminderId));
        if (reminder.getStatus() != ReminderStatus.PENDING) {
            return;
        }
        if (reminder.getTask().getStatus() == TaskStatus.DONE || reminder.getTask().getStatus() == TaskStatus.CANCELLED) {
            throw new ValidationException("Нельзя отложить напоминание для закрытой задачи");
        }
        reminder.setStatus(ReminderStatus.SNOOZED);
        reminderRepository.save(reminder);
        notificationService.cancelReminderNotifications(reminderId);

        TaskJpaEntity task = reminder.getTask();
        createReminder(task.getUserId(), task, until, task.getDeadline(), reminder.getChainStep());
    }

    /**
     * Снятие флага настойчивости (Б1/Б4) — гасит только ещё не сработавшие
     * повторы цепочки, обычные напоминания той же задачи не трогает. Уведомления
     * отменяются по id каждого затронутого напоминания отдельно, не задачи целиком
     * (cancelTaskNotifications), иначе задело бы и обычные напоминания той же задачи.
     */
    @Transactional
    public void cancelPersistentChain(UUID taskId) {
        var pendingChainReminders = reminderRepository
                .findByTaskIdAndStatusOrderByFireAtAsc(taskId, ReminderStatus.PENDING)
                .stream()
                .filter(r -> r.getChainStep() != null)
                .toList();
        for (var reminder : pendingChainReminders) {
            reminder.setStatus(ReminderStatus.CANCELLED);
            reminderRepository.save(reminder);
            notificationService.cancelReminderNotifications(reminder.getId());
        }
    }

    /**
     * Автоматический шаг цепочки (Б3) — вызывается планировщиком, не
     * человеком: повтор, время которого настало без решения (иначе статус
     * уже не PENDING), помечается DISMISSED, и если запас шагов
     * (CHAIN_STEP_GAPS) не исчерпан, создаётся следующий с растущим
     * промежутком. Иначе цепочка тихо заканчивается — новых повторов нет.
     */
    @Transactional
    public void advancePersistentChains(OffsetDateTime now) {
        var due = reminderRepository.findDueChainCandidates(now, PageRequest.of(0, 100));
        for (var candidate : due) {
            // Тот же порядок блокировок, что у ручных команд: задача, затем напоминание.
            var task = taskRepository.findForUpdate(candidate.getTaskId(), candidate.getUserId()).orElse(null);
            if (task == null) {
                continue;
            }
            var reminder = reminderRepository.findForUpdate(candidate.getId(), candidate.getTaskId()).orElse(null);
            if (reminder == null || reminder.getStatus() != ReminderStatus.PENDING
                    || reminder.getChainStep() == null || !reminder.getFireAt().isBefore(now)) {
                continue;
            }
            if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED
                    || !task.isPersistentReminder()) {
                reminder.setStatus(ReminderStatus.CANCELLED);
                notificationService.cancelReminderNotifications(reminder.getId());
                continue;
            }
            reminder.setStatus(ReminderStatus.DISMISSED);
            reminderRepository.save(reminder);
            notificationService.cancelReminderNotifications(reminder.getId());

            int step = reminder.getChainStep();
            if (step < CHAIN_STEP_GAPS.size()) {
                OffsetDateTime nextFireAt = reminder.getFireAt().plus(CHAIN_STEP_GAPS.get(step));
                createReminder(task.getUserId(), task, nextFireAt, task.getDeadline(), step + 1);
            }
        }
    }

    /**
     * Единая точка отмены для всех переходов, после которых напоминания
     * задачи перестают быть актуальными: завершение, отмена, удаление,
     * смена дедлайна/названия. Отменяет только ещё не сработавшие
     * напоминания в reminders — саму задачу не трогает: пропуск не
     * помечает её просроченной (Б4).
     */
    @Transactional
    public void cancelForTask(UUID taskId) {
        reminderRepository.cancelPendingByTaskId(taskId);
        notificationService.cancelTaskNotifications(taskId);
    }

    /**
     * Снимает ровно одно напоминание (А2) — перевод в CANCELLED, история в
     * reminders сохраняется. Уведомления отменяются по id самого напоминания,
     * а не задачи — соседние напоминания той же задачи не задеваются, ради
     * этого таблица и заводилась.
     */
    @Transactional
    public void cancelReminder(UUID taskId, UUID reminderId) {
        var reminder = reminderRepository.findForUpdate(reminderId, taskId)
                .orElseThrow(() -> new ReminderNotFoundException(reminderId));
        if (reminder.getStatus() != ReminderStatus.PENDING) {
            return;
        }
        reminder.setStatus(ReminderStatus.CANCELLED);
        reminderRepository.save(reminder);
        notificationService.cancelReminderNotifications(reminderId);
    }

    private OffsetDateTime clampToDeadline(OffsetDateTime computedFireAt, OffsetDateTime deadline, OffsetDateTime now) {
        return computedFireAt.isBefore(now) ? deadline : computedFireAt;
    }

    private void createReminder(UUID userId, TaskJpaEntity task, OffsetDateTime fireAt,
                                 OffsetDateTime deadlineForDisplay, Integer chainStep) {
        var reminder = new ReminderJpaEntity();
        reminder.setTask(task);
        reminder.setFireAt(fireAt);
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setChainStep(chainStep);
        reminderRepository.save(reminder);
        notificationService.scheduleReminder(userId, task.getId(), reminder.getId(), task.getTitle(), fireAt, deadlineForDisplay);
    }
}
