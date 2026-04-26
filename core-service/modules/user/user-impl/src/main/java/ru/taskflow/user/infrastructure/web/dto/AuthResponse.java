package ru.taskflow.user.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthResponse(
    @JsonProperty("token") String accessToken,
    @JsonProperty("refreshToken") String refreshToken
) {
}