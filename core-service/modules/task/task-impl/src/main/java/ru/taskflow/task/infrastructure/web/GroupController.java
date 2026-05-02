package ru.taskflow.task.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
@Tag(name = "Groups", description = "Управление категориями задач")
public class GroupController {

    private final GroupService groupService;

    @GetMapping
    @Operation(summary = "Список групп", description = "Возвращает все группы пользователя")
    public List<GroupResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return groupService.findAll(user.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать группу", description = "Создаёт новую категорию для задач")
    public GroupResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                @RequestBody @Valid CreateGroupRequest request) {
        return groupService.create(user.userId(), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить группу", description = "Удаляет группу и перемещает задачи в неразобранные")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable UUID id) {
        groupService.delete(user.userId(), id);
    }
}
