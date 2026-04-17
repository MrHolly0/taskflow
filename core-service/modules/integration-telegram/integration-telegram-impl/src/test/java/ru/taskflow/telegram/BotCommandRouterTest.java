package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.telegram.application.BotCommandRouter;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotCommandRouterTest {

    @Mock
    private TaskService taskService;

    @Mock
    private TelegramMessageSender sender;

    @InjectMocks
    private BotCommandRouter router;

    @Test
    void startCommand_sendsWelcome() {
        var message = command("/start", "Иван");

        router.handle(message, UUID.randomUUID());

        verify(sender).sendMessage(eq(100L), anyString());
    }

    @Test
    void helpCommand_sendsHelp() {
        router.handle(command("/help", "User"), UUID.randomUUID());

        verify(sender).sendMessage(eq(100L), anyString());
    }

    @Test
    void todayCommand_sendsTaskList() {
        var userId = UUID.randomUUID();
        when(taskService.findAll(eq(userId), any(TaskFilterRequest.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        router.handle(command("/today", "User"), userId);

        verify(sender).sendMessage(eq(100L), anyString());
    }

    private static TelegramMessage command(String text, String firstName) {
        return new TelegramMessage(1L, new TelegramUser(1L, "user", firstName, null),
                new TelegramChat(100L), text, null);
    }
}