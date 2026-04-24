package ru.taskflow.task.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.util.UUID;

public interface TaskService {

    TaskResponse create(UUID userId, CreateTaskRequest request);

    TaskResponse findById(UUID userId, UUID taskId);

    Page<TaskResponse> findAll(UUID userId, TaskFilterRequest filter, Pageable pageable);

    TaskResponse update(UUID userId, UUID taskId, UpdateTaskRequest request);

    void complete(UUID userId, UUID taskId);

    void delete(UUID userId, UUID taskId);

    TaskResponse confirmDraft(UUID userId, UUID taskId);

    TaskResponse updateDraftTask(UUID userId, UUID taskId, UpdateTaskRequest request);
}
