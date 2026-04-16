package ru.taskflow.task.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.task.api.exception.GroupNotFoundException;
import ru.taskflow.task.api.exception.TaskNotFoundException;
import ru.taskflow.task.infrastructure.persistence.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskServiceImpl implements TaskService {

    private final TaskRepository taskRepository;
    private final GroupRepository groupRepository;
    private final TagRepository tagRepository;
    private final TaskMapper taskMapper;

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

        if (request.groupId() != null) {
            var group = groupRepository.findByIdAndUserId(request.groupId(), userId)
                    .orElseThrow(() -> new GroupNotFoundException(request.groupId()));
            task.setGroup(group);
        }

        if (!request.tags().isEmpty()) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        return taskMapper.toResponse(taskRepository.save(task));
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

        return taskMapper.toResponse(task);
    }

    @Override
    @Transactional
    public void complete(UUID userId, UUID taskId) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(OffsetDateTime.now());
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID taskId) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        task.setDeleted(true);
        task.setDeletedAt(OffsetDateTime.now());
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
}
