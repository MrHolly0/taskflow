package ru.taskflow.user.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record TelegramMiniAppAuthRequest(@NotBlank String initData) {
}