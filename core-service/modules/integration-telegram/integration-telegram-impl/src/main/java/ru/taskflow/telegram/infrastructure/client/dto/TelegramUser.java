package ru.taskflow.telegram.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramUser(
        @JsonProperty("id") long id,
        @JsonProperty("username") String username,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName
) {}