package ru.taskflow.task.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.DigestResponse;
import ru.taskflow.task.api.dto.FocusResponse;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.time.LocalDate;
import java.util.List;
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

    FocusResponse getFocusTasks(UUID userId);

    DigestResponse getDigest(UUID userId, LocalDate date);

    TaskResponse createQuick(UUID userId, CreateTaskRequest request);

    List<String> findGroupNames(UUID userId);
}
