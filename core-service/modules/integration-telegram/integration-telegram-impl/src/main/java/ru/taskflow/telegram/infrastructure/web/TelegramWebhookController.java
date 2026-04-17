package ru.taskflow.telegram.infrastructure.web;

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
import ru.taskflow.telegram.application.UpdateIdempotencyService;
import ru.taskflow.telegram.application.UpdateRouter;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUpdate;

@Slf4j
@RestController
@RequestMapping("/api/telegram/webhook")
public class TelegramWebhookController {

    private final String webhookSecret;
    private final UpdateIdempotencyService idempotencyService;
    private final UpdateRouter updateRouter;

    public TelegramWebhookController(
            @Value("${app.telegram.webhook-secret:}") String webhookSecret,
            UpdateIdempotencyService idempotencyService,
            UpdateRouter updateRouter) {
        this.webhookSecret = webhookSecret;
        this.idempotencyService = idempotencyService;
        this.updateRouter = updateRouter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public void handleUpdate(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
            @RequestBody TelegramUpdate update
    ) {
        if (!webhookSecret.isEmpty() && !webhookSecret.equals(secret)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        if (idempotencyService.isAlreadyProcessed(update.updateId())) {
            log.debug("Duplicate update_id={}, skipping", update.updateId());
            return;
        }

        updateRouter.route(update);
    }
}