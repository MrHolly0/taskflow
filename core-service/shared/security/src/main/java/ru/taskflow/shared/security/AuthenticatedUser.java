package ru.taskflow.shared.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, String telegramUsername) {
}