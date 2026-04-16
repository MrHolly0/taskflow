package ru.taskflow.user.api;

import java.util.UUID;

public interface UserService {

    UserDto findOrCreateByTelegram(long telegramId, String username, String firstName, String lastName);

    UserDto findById(UUID userId);
}