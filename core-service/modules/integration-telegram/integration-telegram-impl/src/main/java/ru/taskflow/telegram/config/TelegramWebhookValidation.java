package ru.taskflow.telegram.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelegramWebhookValidation {
    public TelegramWebhookValidation(@Value("${app.telegram.bot-token:}") String token,
            @Value("${app.public-base-url:}") String publicUrl,
            @Value("${app.telegram.webhook-secret:}") String secret) {
        if(!token.isBlank() && !publicUrl.isBlank() && !secret.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalStateException("Для публичного Telegram webhook нужен секрет длиной 32–256 символов");
    }
}
