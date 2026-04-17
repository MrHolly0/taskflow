package ru.taskflow.telegram.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramMessage(
        @JsonProperty("message_id") long messageId,
        @JsonProperty("from") TelegramUser from,
        @JsonProperty("chat") TelegramChat chat,
        @JsonProperty("text") String text,
        @JsonProperty("voice") TelegramVoice voice
) {}