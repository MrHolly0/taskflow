package ru.taskflow.task.infrastructure.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.shared.security.AuthenticatedUser;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.DigestResponse;
import ru.taskflow.task.api.dto.FocusResponse;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final NlpGatewayService nlpGatewayService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(
            @RequestBody @Valid CreateTaskRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.create(user.userId(), request);
    }

    @GetMapping("/{id}")
    public TaskResponse findById(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.findById(user.userId(), id);
    }

    @GetMapping
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
    public TaskResponse update(
            @PathVariable java.util.UUID id,
            @RequestBody @Valid UpdateTaskRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.update(user.userId(), id, request);
    }

    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        taskService.complete(user.userId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        taskService.delete(user.userId(), id);
    }

    @GetMapping("/focus")
    public FocusResponse getFocusTasks(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.getFocusTasks(user.userId());
    }

    @GetMapping("/digest")
    public DigestResponse getDigest(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date
    ) {
        return taskService.getDigest(user.userId(), date);
    }

    @PostMapping("/quick")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse createQuick(
            @RequestBody @Valid CreateTaskRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return taskService.createQuick(user.userId(), request);
    }

    @PostMapping("/parse-text")
    public Map<String, Object> parseText(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String text = request.get("text");
        String userTimezone = request.getOrDefault("userTimezone", "Europe/Moscow");
        String userLanguage = request.getOrDefault("userLanguage", "ru");

        List<String> groups = taskService.findGroupNames(user.userId());
        var result = nlpGatewayService.parseText(text, userTimezone, groups);
        return Map.of("tasks", result.tasks());
    }
}