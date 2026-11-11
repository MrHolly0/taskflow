package ru.taskflow.task.api;

import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.TaskOperationReceipt;
import ru.taskflow.task.api.dto.TaskUndoResult;
import java.util.Optional;
import java.util.UUID;

public interface TaskOperationService {
    TaskResponse create(UUID userId,CreateTaskRequest request,UUID operationId);
    void complete(UUID userId,UUID taskId,UUID operationId);
    Optional<TaskOperationReceipt> find(UUID userId,UUID operationId);
    TaskUndoResult undo(UUID userId,UUID operationId);
}
