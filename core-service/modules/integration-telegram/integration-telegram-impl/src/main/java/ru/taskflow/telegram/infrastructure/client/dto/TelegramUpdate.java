package ru.taskflow.telegram.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramUpdate(
        @JsonProperty("update_id") long updateId,
        @JsonProperty("message") TelegramMessage message,
        @JsonProperty("callback_query") TelegramCallbackQuery callbackQuery
) {}