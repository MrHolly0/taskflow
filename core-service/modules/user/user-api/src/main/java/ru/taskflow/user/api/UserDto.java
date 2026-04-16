package ru.taskflow.user.api;

import java.util.UUID;

public record UserDto(UUID id, long telegramId, String username, String firstName, String lastName) {
}