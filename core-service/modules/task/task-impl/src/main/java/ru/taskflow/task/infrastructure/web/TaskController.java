package ru.taskflow.task.infrastructure.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(
            @RequestBody @Valid CreateTaskRequest request,
            // TODO день 3: заменить на @AuthenticationPrincipal UUID userId
            @RequestHeader("X-User-Id") UUID userId
    ) {
        return taskService.create(userId, request);
    }

    @GetMapping("/{id}")
    public TaskResponse findById(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") UUID userId
    ) {
        return taskService.findById(userId, id);
    }

    @GetMapping
    public Page<TaskResponse> findAll(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) String tag,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        var filter = new TaskFilterRequest(groupId, status, priority, tag);
        return taskService.findAll(userId, filter, pageable);
    }

    @PatchMapping("/{id}")
    public TaskResponse update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateTaskRequest request,
            @RequestHeader("X-User-Id") UUID userId
    ) {
        return taskService.update(userId, id, request);
    }

    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") UUID userId
    ) {
        taskService.complete(userId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") UUID userId
    ) {
        taskService.delete(userId, id);
    }
}
