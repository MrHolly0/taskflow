package ru.taskflow.task.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.AssistantTaskSelector;
import ru.taskflow.task.api.dto.AssistantTaskSelection;
import ru.taskflow.task.api.dto.DigestResponse;
import ru.taskflow.task.api.dto.FocusResponse;
import ru.taskflow.task.api.dto.FocusHintResponse;
import ru.taskflow.task.api.dto.PlannedDateSuggestionResponse;
import ru.taskflow.task.api.dto.ReminderResponse;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.TaskStatsResponse;
import ru.taskflow.task.api.dto.TaskTransferResult;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TaskService {

    AssistantTaskSelection selectForAssistant(UUID userId,
            AssistantTaskSelector selector);

    TaskResponse create(UUID userId, CreateTaskRequest request);

    TaskResponse findById(UUID userId, UUID taskId);

    Page<TaskResponse> findAll(UUID userId, TaskFilterRequest filter, Pageable pageable);

    TaskResponse update(UUID userId, UUID taskId, UpdateTaskRequest request);

    void complete(UUID userId, UUID taskId);

    /**
     * Напоминание на конкретное время независимо от срока задачи (Б2) — срок
     * обязательство перед кем-то, напоминание просьба к себе, и задача может
     * не иметь дедлайна вовсе. Не меняет deadline задачи.
     */
    void scheduleReminder(UUID userId, UUID taskId, OffsetDateTime fireAt);

    /**
     * Ещё не сработавшие напоминания задачи (А1), от ближайшего к дальнему.
     * Пустой список, если их нет — не null.
     */
    List<ReminderResponse> getReminders(UUID userId, UUID taskId);

    /**
     * Снимает ровно одно напоминание (А2) — перевод в отменённый статус,
     * не удаление; остальные напоминания той же задачи не затрагиваются.
     */
    void cancelReminder(UUID userId, UUID taskId, UUID reminderId);

    /**
     * Точка решения по настойчивому повтору (Б2) — «отложить на срок»:
     * данный повтор помечается отложенным, следующий появляется в выбранное
     * время. Автоматический запас повторов (Б3) не расходует.
     */
    void snoozeReminder(UUID userId, UUID taskId, UUID reminderId, OffsetDateTime until);

    void delete(UUID userId, UUID taskId);

    /**
     * Снимает правило повтора с задачи, если оно есть — саму задачу и её
     * будущие вхождения (уже созданные) не трогает, только останавливает
     * порождение новых при следующем закрытии.
     */
    void clearRecurrence(UUID userId, UUID taskId);

    /**
     * @param availableMinutes сколько времени сейчас есть, опционально — если задано,
     *                         отбор пропускает задачи с оценкой длительности больше него;
     *                         задачи без оценки участвуют всегда, независимо от значения
     */
    FocusResponse getFocusTasks(UUID userId, Integer availableMinutes);

    FocusHintResponse getFocusHint(UUID userId, UUID taskId);

    PlannedDateSuggestionResponse suggestPlannedDate(UUID userId, UUID taskId);

    FocusResponse getUpcomingFocusTasks(UUID userId, Integer availableMinutes);

    DigestResponse getDigest(UUID userId, LocalDate date);

    TaskStatsResponse getStats(UUID userId);

    TaskResponse createQuick(UUID userId, CreateTaskRequest request);

    List<String> findGroupNames(UUID userId);

    int clearCompleted(UUID userId);

    List<TaskResponse> findAssistantContext(UUID userId, int limit);

    /**
     * Поиск по подстроке в названии и описании. Возвращает не более limit совпадений,
     * жёсткий предел — 20: результат уходит в промпт модели.
     */
    List<TaskResponse> search(UUID userId, String query, boolean includeCompleted, int limit);

    /**
     * Переносит задачи, группы и метки с одной учётки на другую. Массовым
     * UPDATE, без выборки — переносит и мягко удалённые задачи тоже.
     * Идемпотентно: повторный вызов после того, как у from ничего не осталось,
     * просто ничего не находит и возвращает нули.
     */
    TaskTransferResult transferOwnership(UUID from, UUID to);

    /**
     * Сколько задач, групп и меток сейчас у учётки — предпросмотр для
     * диалога согласия на слияние, без единой записи. Мягко удалённые задачи
     * не считаются: это число показывается человеку, а не техническое.
     */
    TaskTransferResult countOwnership(UUID userId);
}
