package ru.taskflow.user.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.taskflow.shared.security.AuthenticatedUser;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.user.api.dto.UserSettingsDto;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
@Tag(name = "Settings", description = "Настройки пользователя")
public class SettingsController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Получить настройки")
    public UserSettingsDto getSettings(@AuthenticationPrincipal AuthenticatedUser user) {
        return userService.getSettings(user.userId());
    }

    @PutMapping
    @Operation(summary = "Обновить настройки", description = "Частичное обновление — передавай только изменяемые поля")
    public UserSettingsDto updateSettings(
            @RequestBody UpdateSettingsRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        userService.updateSettings(user.userId(), request);
        return userService.getSettings(user.userId());
    }

    @PostMapping("/activity")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void activity(@AuthenticationPrincipal AuthenticatedUser user) {
        userService.recordActivity(user.userId());
    }
}
