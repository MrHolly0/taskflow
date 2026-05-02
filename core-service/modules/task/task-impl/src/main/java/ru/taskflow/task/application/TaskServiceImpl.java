package ru.taskflow.task.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.DigestResponse;
import ru.taskflow.task.api.dto.FocusResponse;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.task.api.exception.GroupNotFoundException;
import ru.taskflow.task.api.exception.TaskNotFoundException;
import ru.taskflow.task.infrastructure.persistence.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Основной сервис управления задачами пользователя.
 *
 * Предоставляет операции для создания, чтения, обновления и удаления задач,
 * а также специализированные эндпоинты для режима фокуса и дайджестов.
 * Поддерживает автоматическую группировку, теги, уведомления и аудит.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskServiceImpl implements TaskService {

    private final TaskRepository taskRepository;
    private final GroupRepository groupRepository;
    private final TagRepository tagRepository;
    private final TaskMapper taskMapper;
    private final NotificationService notificationService;
    private final AuditService auditService;

    /**
     * Создаёт новую задачу для пользователя.
     *
     * Задача изначально создаётся в статусе draft. Если указана группа,
     * автоматически создаётся, если её не существует.
     *
     * @param userId ID пользователя
     * @param request параметры новой задачи (название, описание, приоритет и т.д.)
     * @return созданная задача с ID
     */
    @Override
    @Transactional
    public TaskResponse create(UUID userId, CreateTaskRequest request) {
        var task = new TaskJpaEntity();
        task.setUserId(userId);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setPriority(request.priority());
        task.setDeadline(request.deadline());
        task.setEstimateMinutes(request.estimateMinutes());
        task.setSource(request.source());
        task.setDraft(true);

        task.setGroup(resolveGroup(userId, request.groupId(), request.groupName()));

        if (!request.tags().isEmpty()) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity savedTask = taskRepository.save(task);
        auditService.record(userId, savedTask.getId(), AuditEventType.CREATED, null);
        return taskMapper.toResponse(savedTask);
    }

    /**
     * Получает задачу по ID.
     *
     * @param userId ID пользователя
     * @param taskId ID задачи
     * @return данные задачи
     * @throws TaskNotFoundException если задача не найдена или принадлежит другому пользователю
     */
    @Override
    public TaskResponse findById(UUID userId, UUID taskId) {
        return taskRepository.findByIdAndUserId(taskId, userId)
                .map(taskMapper::toResponse)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /**
     * Получает список всех задач пользователя с фильтрацией.
     *
     * Поддерживает фильтрацию по группе, статусу, приоритету и тегам.
     * Результаты постраничные.
     *
     * @param userId ID пользователя
     * @param filter критерии фильтрации
     * @param pageable параметры страницы и сортировки
     * @return страница с задачами
     */
    @Override
    public Page<TaskResponse> findAll(UUID userId, TaskFilterRequest filter, Pageable pageable) {
        return taskRepository.findAllWithFilter(
                userId,
                filter.groupId(),
                filter.status(),
                filter.priority(),
                filter.tag(),
                pageable
        ).map(taskMapper::toResponse);
    }

    /**
     * Обновляет параметры задачи.
     *
     * Если изменился дедлайн или название, отправляет обновление в сервис уведомлений.
     * Записывает событие в аудит.
     *
     * @param userId ID пользователя
     * @param taskId ID задачи
     * @param request новые параметры
     * @return обновленная задача
     * @throws TaskNotFoundException если задача не найдена
     */
    @Override
    @Transactional
    public TaskResponse update(UUID userId, UUID taskId, UpdateTaskRequest request) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        boolean deadlineChanged = request.deadline() != null;
        boolean titleChanged = request.title() != null;

        if (request.title() != null) task.setTitle(request.title());
        if (request.description() != null) task.setDescription(request.description());
        if (request.priority() != null) task.setPriority(request.priority());
        if (request.deadline() != null) task.setDeadline(request.deadline());
        if (request.estimateMinutes() != null) task.setEstimateMinutes(request.estimateMinutes());

        if (request.status() != null) {
            task.setStatus(request.status());
            if (request.status() == TaskStatus.DONE && task.getCompletedAt() == null) {
                task.setCompletedAt(OffsetDateTime.now());
            }
        }

        if (request.groupId() != null) {
            var group = groupRepository.findByIdAndUserId(request.groupId(), userId)
                    .orElseThrow(() -> new GroupNotFoundException(request.groupId()));
            task.setGroup(group);
        }

        if (request.tags() != null) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity updatedTask = taskRepository.save(task);
        Map<String, Object> delta = buildDelta(task, request);
        auditService.record(userId, taskId, AuditEventType.UPDATED, delta);

        if (deadlineChanged || titleChanged) {
            notificationService.cancelTaskNotifications(taskId);
            if (updatedTask.getDeadline() != null) {
                notificationService.scheduleTaskReminder(userId, taskId, updatedTask.getTitle(), updatedTask.getDeadline());
            }
        }

        return taskMapper.toResponse(updatedTask);
    }

    /**
     * Отмечает задачу как выполненную.
     *
     * Устанавливает статус DONE и фиксирует время завершения.
     *
     * @param userId ID пользователя
     * @param taskId ID задачи
     * @throws TaskNotFoundException если задача не найдена
     */
    @Override
    @Transactional
    public void complete(UUID userId, UUID taskId) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(OffsetDateTime.now());
        taskRepository.save(task);
        auditService.record(userId, taskId, AuditEventType.STATUS_CHANGED, Map.of("status", "DONE"));
    }

    /**
     * Удаляет задачу (мягкое удаление).
     *
     * Задача помечается как удалённая с фиксацией времени.
     *
     * @param userId ID пользователя
     * @param taskId ID задачи
     * @throws TaskNotFoundException если задача не найдена
     */
    @Override
    @Transactional
    public void delete(UUID userId, UUID taskId) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        task.setDeleted(true);
        task.setDeletedAt(OffsetDateTime.now());
        taskRepository.save(task);
        auditService.record(userId, taskId, AuditEventType.DELETED, null);
    }

    @Override
    @Transactional
    public TaskResponse confirmDraft(UUID userId, UUID taskId) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        task.setDraft(false);

        if (task.getDeadline() != null) {
            notificationService.scheduleTaskReminder(userId, taskId, task.getTitle(), task.getDeadline());
        }

        TaskJpaEntity savedTask = taskRepository.save(task);
        auditService.record(userId, taskId, AuditEventType.UPDATED, Map.of("isDraft", false));
        return taskMapper.toResponse(savedTask);
    }

    @Override
    @Transactional
    public TaskResponse updateDraftTask(UUID userId, UUID taskId, UpdateTaskRequest request) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (!task.isDraft()) {
            throw new IllegalStateException("Cannot edit non-draft task");
        }

        if (request.title() != null) task.setTitle(request.title());
        if (request.description() != null) task.setDescription(request.description());
        if (request.priority() != null) task.setPriority(request.priority());
        if (request.deadline() != null) task.setDeadline(request.deadline());
        if (request.estimateMinutes() != null) task.setEstimateMinutes(request.estimateMinutes());

        if (request.groupId() != null) {
            var group = groupRepository.findByIdAndUserId(request.groupId(), userId)
                    .orElseThrow(() -> new GroupNotFoundException(request.groupId()));
            task.setGroup(group);
        }

        if (request.tags() != null) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity savedTask = taskRepository.save(task);
        return taskMapper.toResponse(savedTask);
    }

    private List<TagJpaEntity> resolveOrCreateTags(UUID userId, List<String> tagNames) {
        var existing = tagRepository.findAllByUserIdAndNameIn(userId, tagNames);
        var existingNames = existing.stream().map(TagJpaEntity::getName).toList();

        List<TagJpaEntity> result = new ArrayList<>(existing);
        for (String name : tagNames) {
            if (!existingNames.contains(name)) {
                var tag = new TagJpaEntity();
                tag.setUserId(userId);
                tag.setName(name);
                result.add(tagRepository.save(tag));
            }
        }
        return result;
    }

    private GroupJpaEntity resolveGroup(UUID userId, UUID groupId, String groupName) {
        if (groupId != null) {
            return groupRepository.findByIdAndUserId(groupId, userId)
                    .orElseThrow(() -> new GroupNotFoundException(groupId));
        }
        if (groupName != null && !groupName.isBlank()) {
            return groupRepository.findByUserIdAndName(userId, groupName)
                    .orElseGet(() -> {
                        var g = new GroupJpaEntity();
                        g.setUserId(userId);
                        g.setName(groupName);
                        return groupRepository.save(g);
                    });
        }
        return null;
    }

    private Map<String, Object> buildDelta(TaskJpaEntity task, UpdateTaskRequest request) {
        Map<String, Object> delta = new HashMap<>();
        if (request.title() != null && !request.title().equals(task.getTitle())) {
            delta.put("title", request.title());
        }
        if (request.description() != null && !request.description().equals(task.getDescription())) {
            delta.put("description", request.description());
        }
        if (request.priority() != null && !request.priority().equals(task.getPriority())) {
            delta.put("priority", request.priority());
        }
        if (request.deadline() != null && !request.deadline().equals(task.getDeadline())) {
            delta.put("deadline", request.deadline());
        }
        if (request.estimateMinutes() != null && !request.estimateMinutes().equals(task.getEstimateMinutes())) {
            delta.put("estimateMinutes", request.estimateMinutes());
        }
        if (request.status() != null && !request.status().equals(task.getStatus())) {
            delta.put("status", request.status());
        }
        return delta.isEmpty() ? null : delta;
    }

    /**
     * Получает ограниченный список задач для режима фокуса.
     *
     * Возвращает до 3 актуальных задач на сегодня (с дедлайном до конца дня),
     * исключая выполненные.
     *
     * @param userId ID пользователя
     * @return ответ с задачами для фокуса
     */
    @Override
    public FocusResponse getFocusTasks(UUID userId) {
        var endOfToday = OffsetDateTime.now(ZoneOffset.UTC)
                .withHour(23).withMinute(59).withSecond(59).withNano(0);
        var tasks = taskRepository.findFocusTasks(userId, TaskStatus.DONE, endOfToday)
                .stream()
                .limit(3)
                .map(taskMapper::toResponse)
                .toList();
        return new FocusResponse(tasks);
    }

    /**
     * Получает дайджест по задачам на конкретную дату.
     *
     * Агрегирует статистику по выполненным, просроченным и активным задачам.
     *
     * @param userId ID пользователя
     * @param date дата для дайджеста
     * @return ответ с статистикой и списком задач
     */
    @Override
    public DigestResponse getDigest(UUID userId, LocalDate date) {
        var startOfDay = date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        var allTasks = taskRepository.findDigestTasks(userId, startOfDay, TaskStatus.DONE);

        var topTasks = allTasks.stream()
                .limit(5)
                .map(taskMapper::toResponse)
                .toList();

        long totalTasks = allTasks.size();
        long completedToday = allTasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.DONE)
                .count();

        long overdueTasks = allTasks.stream()
                .filter(t -> t.getDeadline() != null && t.getDeadline().isBefore(OffsetDateTime.now()) && t.getStatus() != TaskStatus.DONE)
                .count();

        return new DigestResponse(topTasks, totalTasks, completedToday, overdueTasks);
    }

    /**
     * Создаёт задачу в статусе активной (не draft) с быстрым способом.
     *
     * Используется для быстрого добавления задач через боты и интеграции.
     * Автоматически расписывает напоминания если указан дедлайн.
     *
     * @param userId ID пользователя
     * @param request параметры новой задачи
     * @return созданная активная задача
     */
    @Override
    @Transactional
    public TaskResponse createQuick(UUID userId, CreateTaskRequest request) {
        var task = new TaskJpaEntity();
        task.setUserId(userId);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setPriority(request.priority());
        task.setDeadline(request.deadline());
        task.setEstimateMinutes(request.estimateMinutes());
        task.setSource(request.source());
        task.setDraft(false);
        task.setStatus(TaskStatus.TODO);

        task.setGroup(resolveGroup(userId, request.groupId(), request.groupName()));

        if (!request.tags().isEmpty()) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity savedTask = taskRepository.save(task);

        if (task.getDeadline() != null) {
            notificationService.scheduleTaskReminder(userId, savedTask.getId(), savedTask.getTitle(), task.getDeadline());
        }

        return taskMapper.toResponse(savedTask);
    }

    @Override
    public List<String> findGroupNames(UUID userId) {
        return groupRepository.findAllByUserId(userId).stream()
            .map(GroupJpaEntity::getName)
            .toList();
    }
}
