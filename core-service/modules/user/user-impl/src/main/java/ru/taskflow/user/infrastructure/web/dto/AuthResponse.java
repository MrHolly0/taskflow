package ru.taskflow.user.infrastructure.web.dto;

public record AuthResponse(String accessToken, String refreshToken) {
}