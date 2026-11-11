package ru.taskflow.telegram.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record TelegramWidgetQuery(String id, TelegramUser from, Chat chat,
                                  @JsonProperty("message_id") Long messageId, JsonNode type, String data) {
    public record Chat(long id, String type) {}
}
