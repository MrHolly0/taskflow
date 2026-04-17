package ru.taskflow.telegram.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramChat(
        @JsonProperty("id") long id
) {}