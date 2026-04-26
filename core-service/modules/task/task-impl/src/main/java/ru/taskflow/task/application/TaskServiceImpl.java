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

        if (request.groupId() != null) {
            var group = groupRepository.findByIdAndUserId(request.groupId(), userId)
                    .orElseThrow(() -> new GroupNotFoundException(request.groupId()));
            task.setGroup(group);
        }

        if (!request.tags().isEmpty()) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity savedTask = taskRepository.save(task);
        auditService.record(userId, savedTask.getId(), AuditEventType.CREATED, null);
        return taskMapper.toResponse(savedTask);
    }

    @Override
    public TaskResponse findById(UUID userId, UUID taskId) {
        return taskRepository.findByIdAndUserId(taskId, userId)
                .map(taskMapper::toResponse)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

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

    @Override
    @Transactional
    public TaskResponse update(UUID userId, UUID taskId, UpdateTaskRequest request) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        boolean deadlineChanged = request.deadline() != null;

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

        if (deadlineChanged) {
            notificationService.cancelTaskNotifications(taskId);
            notificationService.scheduleTaskReminder(userId, taskId, updatedTask.getTitle(), request.deadline());
        }

        return taskMapper.toResponse(updatedTask);
    }

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

    @Override
    public FocusResponse getFocusTasks(UUID userId) {
        var tasks = taskRepository.findFocusTasks(userId, TaskStatus.DONE)
                .stream()
                .limit(3)
                .map(taskMapper::toResponse)
                .toList();
        return new FocusResponse(tasks);
    }

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

        if (request.groupId() != null) {
            var group = groupRepository.findByIdAndUserId(request.groupId(), userId)
                    .orElseThrow(() -> new GroupNotFoundException(request.groupId()));
            task.setGroup(group);
        }

        if (!request.tags().isEmpty()) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity savedTask = taskRepository.save(task);

        if (task.getDeadline() != null) {
            notificationService.scheduleTaskReminder(userId, savedTask.getId(), savedTask.getTitle(), task.getDeadline());
        }

        return taskMapper.toResponse(savedTask);
    }
}
