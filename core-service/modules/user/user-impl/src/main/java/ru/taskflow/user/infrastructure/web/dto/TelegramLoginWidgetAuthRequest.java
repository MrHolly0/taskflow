package ru.taskflow.user.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record TelegramLoginWidgetAuthRequest(@NotNull Map<String, String> fields) {
}