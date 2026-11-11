package ru.taskflow.telegram.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramUpdate(
        @JsonProperty("update_id") long updateId,
        @JsonProperty("message") TelegramMessage message,
        @JsonProperty("callback_query") TelegramCallbackQuery callbackQuery,
        @JsonProperty("widget_query") TelegramWidgetQuery widgetQuery
) {
    public TelegramUpdate(long updateId, TelegramMessage message, TelegramCallbackQuery callbackQuery) {
        this(updateId,message,callbackQuery,null);
    }
}