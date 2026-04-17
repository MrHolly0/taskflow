package ru.taskflow.telegram.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramCallbackQuery(
        @JsonProperty("id") String id,
        @JsonProperty("from") TelegramUser from,
        @JsonProperty("message") TelegramMessage message,
        @JsonProperty("data") String data
) {}