package ru.taskflow.telegram.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.telegram.application.TelegramUpdateInbox;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUpdate;

@Slf4j
@RestController
@RequestMapping("/api/telegram/webhook")
@Tag(name = "Telegram", description = "Webhook для получения обновлений от Telegram Bot API")
public class TelegramWebhookController {

    private final String webhookSecret;
    private final TelegramUpdateInbox inbox;

    public TelegramWebhookController(
            @Value("${app.telegram.webhook-secret:}") String webhookSecret,
            TelegramUpdateInbox inbox) {
        this.webhookSecret = webhookSecret;
        this.inbox = inbox;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Webhook Telegram", description = "Получает обновления от Telegram Bot API (сообщения, команды, обратные вызовы). Проверяет подпись, выполняет дедупликацию по update_id")
    public void handleUpdate(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
            @RequestBody TelegramUpdate update
    ) {
        if (webhookSecret.isBlank() || secret == null || !java.security.MessageDigest.isEqual(
                webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        inbox.enqueue(update);
    }
}
