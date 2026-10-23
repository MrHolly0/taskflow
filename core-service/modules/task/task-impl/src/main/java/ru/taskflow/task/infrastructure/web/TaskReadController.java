package ru.taskflow.task.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.taskflow.shared.security.AuthenticatedUser;
import ru.taskflow.task.application.read.TaskReadService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskReadController {
    private final TaskReadService service;

    @GetMapping("/view")
    public TaskReadService.TaskPage page(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue="active") String status, @RequestParam(defaultValue="all") String group,
            @RequestParam(defaultValue="") String query, @RequestParam(defaultValue="all") String context,
            @RequestParam(defaultValue="default") String order, @RequestParam(required=false) OffsetDateTime completedSince,
            @RequestParam(required=false) OffsetDateTime completedUntil,
            @RequestParam(defaultValue="") List<UUID> revealIds, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return service.page(user.userId(), new TaskReadService.Filter(status, group, query, context, order,
                completedSince, completedUntil, revealIds), page, size);
    }

    @GetMapping("/overview")
    public TaskReadService.Overview overview(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.overview(user.userId());
    }
}
