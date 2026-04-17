package ru.taskflow.telegram.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramVoice(
        @JsonProperty("file_id") String fileId,
        @JsonProperty("duration") int duration
) {}