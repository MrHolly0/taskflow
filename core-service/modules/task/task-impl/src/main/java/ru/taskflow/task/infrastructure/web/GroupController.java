package ru.taskflow.task.infrastructure.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.taskflow.shared.security.AuthenticatedUser;
import ru.taskflow.task.api.dto.CreateGroupRequest;
import ru.taskflow.task.api.dto.GroupResponse;
import ru.taskflow.task.application.GroupService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @GetMapping
    public List<GroupResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return groupService.findAll(user.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                @RequestBody @Valid CreateGroupRequest request) {
        return groupService.create(user.userId(), request);
    }
}
