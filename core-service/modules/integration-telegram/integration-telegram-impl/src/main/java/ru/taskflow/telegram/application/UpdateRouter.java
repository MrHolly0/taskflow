package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserService;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramCallbackQuery;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUpdate;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;

import java.util.UUID;

/**
 * Маршрутизатор обновлений от Telegram Bot API.
 *
 * Распределяет входящие события (сообщения, команды, обратные вызовы)
 * на соответствующие обработчики. Разрешает Telegram ID в ID пользователей системы.
 */
@Service
@RequiredArgsConstructor
public class UpdateRouter {

    private final UserService userService;
    private final BotCommandRouter commandRouter;
    private final TextMessageHandler textHandler;
    private final VoiceMessageHandler voiceHandler;
    private final CallbackHandler callbackHandler;

    /**
     * Маршрутизирует входящее обновление от Telegram.
     *
     * Определяет тип события (команда, текст, голос, callback) и передаёт
     * на соответствующий обработчик. Автоматически создаёт пользователей при необходимости.
     *
     * @param update обновление от Telegram Bot API
     */
    public void route(TelegramUpdate update) {
        TelegramCallbackQuery callback = update.callbackQuery();
        if (callback != null) {
            UUID userId = resolveUser(callback.from()).id();
            callbackHandler.handle(callback, userId);
            return;
        }

        TelegramMessage message = update.message();
        if (message == null || message.from() == null) return;

        UUID userId = resolveUser(message.from()).id();

        if (message.voice() != null) {
            voiceHandler.handle(message, userId);
        } else if (message.text() != null && message.text().startsWith("/")) {
            commandRouter.handle(message, userId);
        } else if (message.text() != null) {
            textHandler.handle(message, userId);
        }
    }

    private UserDto resolveUser(TelegramUser tgUser) {
        return userService.findOrCreateByTelegram(
                tgUser.id(), tgUser.username(), tgUser.firstName(), tgUser.lastName());
    }
}