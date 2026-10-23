package ru.taskflow.task.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import ru.taskflow.shared.security.AuthenticatedUser;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.application.TaskCreationService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.DigestResponse;
import ru.taskflow.task.api.dto.FocusResponse;
import ru.taskflow.task.api.dto.ReminderResponse;
import ru.taskflow.task.api.dto.PlannedDateSuggestionResponse;
import ru.taskflow.task.api.dto.ScheduleReminderRequest;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.TaskStatsResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Tag(name = "Tasks", description = "Управление задачами: создание, чтение, обновление, удаление")
public class TaskController {

    private final TaskService taskService;
    private final TaskCreationService taskCreationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать задачу", description = "Создаёт новую задачу из формы или голоса")
    public TaskResponse create(
            @RequestBody @Valid CreateTaskRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String requestKey,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskCreationService.create(user.userId(), request, requestKey, false);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить задачу", description = "Возвращает одну задачу по ID")
    public TaskResponse findById(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.findById(user.userId(), id);
    }

    @GetMapping
    @Operation(summary = "Список всех задач", description = "Возвращает все задачи с фильтрацией и постраничной выборкой")
    public Page<TaskResponse> findAll(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) java.util.UUID groupId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) String tag,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        var filter = new TaskFilterRequest(groupId, status, priority, tag);
        return taskService.findAll(user.userId(), filter, pageable);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Обновить задачу", description = "Обновляет название, дедлайн, приоритет и статус")
    public TaskResponse update(
            @PathVariable java.util.UUID id,
            @RequestBody @Valid UpdateTaskRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.update(user.userId(), id, request);
    }

    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отметить как выполненную", description = "Меняет статус на DONE и отменяет напоминания")
    public void complete(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        taskService.complete(user.userId(), id);
    }

    @DeleteMapping("/{id}/recurrence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Снять повтор", description = "Останавливает порождение новых вхождений при следующем закрытии задачи")
    public void clearRecurrence(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        taskService.clearRecurrence(user.userId(), id);
    }

    @PostMapping("/{id}/reminders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Добавить напоминание", description = "Планирует напоминание на конкретное время независимо от срока задачи")
    public void scheduleReminder(
            @PathVariable java.util.UUID id,
            @RequestBody @Valid ScheduleReminderRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        taskService.scheduleReminder(user.userId(), id, request.fireAt());
    }

    @GetMapping("/{id}/reminders")
    @Operation(summary = "Напоминания задачи", description = "Возвращает ещё не сработавшие напоминания задачи по возрастанию времени")
    public List<ReminderResponse> getReminders(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.getReminders(user.userId(), id);
    }

    @DeleteMapping("/{id}/reminders/{reminderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Снять напоминание", description = "Отменяет одно напоминание задачи, не затрагивая остальные")
    public void cancelReminder(
            @PathVariable java.util.UUID id,
            @PathVariable java.util.UUID reminderId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        taskService.cancelReminder(user.userId(), id, reminderId);
    }

    @PostMapping("/{id}/reminders/{reminderId}/snooze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отложить настойчивый повтор", description = "Точка решения по цепочке настойчивых "
            + "напоминаний: следующий повтор появится в выбранное время, автоматический запас повторов не тратится")
    public void snoozeReminder(
            @PathVariable java.util.UUID id,
            @PathVariable java.util.UUID reminderId,
            @RequestBody @Valid ScheduleReminderRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        taskService.snoozeReminder(user.userId(), id, reminderId, request.fireAt());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить задачу", description = "Мягко удаляет задачу (is_deleted = true)")
    public void delete(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        taskService.delete(user.userId(), id);
    }

    @DeleteMapping("/completed")
    @Operation(summary = "Очистить выполненные", description = "Скрывает все DONE и CANCELLED задачи пользователя")
    public Map<String, Integer> clearCompleted(@AuthenticationPrincipal AuthenticatedUser user) {
        int count = taskService.clearCompleted(user.userId());
        return Map.of("cleared", count);
    }

    @GetMapping("/focus")
    @Operation(summary = "Режим фокуса", description = "Возвращает 1–3 приоритетные задачи с ближайшим дедлайном")
    public FocusResponse getFocusTasks(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Integer availableMinutes
    ) {
        return taskService.getFocusTasks(user.userId(), availableMinutes);
    }

    @GetMapping("/{id}/focus-hint")
    @Operation(summary = "Первый шаг задачи в режиме фокуса")
    public ru.taskflow.task.api.dto.FocusHintResponse getFocusHint(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.getFocusHint(user.userId(), id);
    }

    @PostMapping("/{id}/planned-date-suggestion")
    @Operation(summary = "Подобрать день исполнения для бессрочной задачи")
    public PlannedDateSuggestionResponse suggestPlannedDate(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.suggestPlannedDate(user.userId(), id);
    }

    @GetMapping("/focus/upcoming")
    @Operation(summary = "Режим фокуса — что дальше", description = "Возвращает 1–3 задачи с дедлайном позже сегодняшнего, для просмотра после закрытия плана на сегодня")
    public FocusResponse getUpcomingFocusTasks(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Integer availableMinutes
    ) {
        return taskService.getUpcomingFocusTasks(user.userId(), availableMinutes);
    }

    @GetMapping("/digest")
    @Operation(summary = "Дайджест на дату", description = "Возвращает все задачи на конкретную дату (по умолчанию сегодня)")
    public DigestResponse getDigest(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date
    ) {
        return taskService.getDigest(user.userId(), date);
    }

    @GetMapping("/stats")
    @Operation(summary = "Статистика задач", description = "Возвращает данные для графиков, включая скрытые выполненные задачи")
    public TaskStatsResponse getStats(@AuthenticationPrincipal AuthenticatedUser user) {
        return taskService.getStats(user.userId());
    }

    @PostMapping("/quick")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Быстрый ввод", description = "Создаёт задачу в режиме черновика для подтверждения")
    public TaskResponse createQuick(
            @RequestBody @Valid CreateTaskRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String requestKey,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskCreationService.create(user.userId(), request, requestKey, true);
    }
}
