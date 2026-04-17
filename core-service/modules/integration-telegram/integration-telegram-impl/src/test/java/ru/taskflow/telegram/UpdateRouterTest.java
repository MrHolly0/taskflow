package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserService;
import ru.taskflow.telegram.application.BotCommandRouter;
import ru.taskflow.telegram.application.CallbackHandler;
import ru.taskflow.telegram.application.TextMessageHandler;
import ru.taskflow.telegram.application.UpdateRouter;
import ru.taskflow.telegram.application.VoiceMessageHandler;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramCallbackQuery;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUpdate;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramVoice;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateRouterTest {

    @Mock
    private UserService userService;

    @Mock
    private BotCommandRouter commandRouter;

    @Mock
    private TextMessageHandler textHandler;

    @Mock
    private VoiceMessageHandler voiceHandler;

    @Mock
    private CallbackHandler callbackHandler;

    @InjectMocks
    private UpdateRouter router;

    @Test
    void textMessage_callsTextHandler() {
        var userId = stubUser();
        var message = textMessage("hello");

        router.route(new TelegramUpdate(1L, message, null));

        verify(textHandler).handle(message, userId);
        verifyNoInteractions(commandRouter, voiceHandler, callbackHandler);
    }

    @Test
    void command_callsCommandRouter() {
        var userId = stubUser();
        var message = textMessage("/start");

        router.route(new TelegramUpdate(1L, message, null));

        verify(commandRouter).handle(message, userId);
        verifyNoInteractions(textHandler, voiceHandler, callbackHandler);
    }

    @Test
    void voiceMessage_callsVoiceHandler() {
        var userId = stubUser();
        var message = voiceMessage();

        router.route(new TelegramUpdate(1L, message, null));

        verify(voiceHandler).handle(message, userId);
        verifyNoInteractions(textHandler, commandRouter, callbackHandler);
    }

    @Test
    void callbackQuery_callsCallbackHandler() {
        var userId = UUID.randomUUID();
        var tgUser = new TelegramUser(1L, "u", "Name", null);
        when(userService.findOrCreateByTelegram(anyLong(), anyString(), anyString(), any()))
                .thenReturn(new UserDto(userId, 1L, "u", "Name", null));
        var cb = new TelegramCallbackQuery("cb1", tgUser,
                new TelegramMessage(1L, tgUser, new TelegramChat(100L), null, null), "complete:abc");

        router.route(new TelegramUpdate(1L, null, cb));

        verify(callbackHandler).handle(cb, userId);
        verifyNoInteractions(textHandler, commandRouter, voiceHandler);
    }

    private UUID stubUser() {
        var userId = UUID.randomUUID();
        when(userService.findOrCreateByTelegram(anyLong(), anyString(), anyString(), any()))
                .thenReturn(new UserDto(userId, 1L, "u", "Name", null));
        return userId;
    }

    private static TelegramMessage textMessage(String text) {
        return new TelegramMessage(1L, new TelegramUser(1L, "u", "Name", null),
                new TelegramChat(100L), text, null);
    }

    private static TelegramMessage voiceMessage() {
        return new TelegramMessage(1L, new TelegramUser(1L, "u", "Name", null),
                new TelegramChat(100L), null, new TelegramVoice("file1", 3));
    }
}