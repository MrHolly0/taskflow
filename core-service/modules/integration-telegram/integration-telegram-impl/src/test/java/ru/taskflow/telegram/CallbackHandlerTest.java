package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.telegram.application.CallbackHandler;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramCallbackQuery;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CallbackHandlerTest {

    @Mock
    private TaskService taskService;

    @Mock
    private TelegramMessageSender sender;

    @InjectMocks
    private CallbackHandler handler;

    @Test
    void completeCallback_completesTask() {
        var userId = UUID.randomUUID();
        var taskId = UUID.randomUUID();

        handler.handle(callback("complete:" + taskId), userId);

        verify(taskService).complete(userId, taskId);
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void deleteCallback_deletesTask() {
        var userId = UUID.randomUUID();
        var taskId = UUID.randomUUID();

        handler.handle(callback("delete:" + taskId), userId);

        verify(taskService).delete(userId, taskId);
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    @Test
    void invalidCallbackData_answersWithError() {
        handler.handle(callback("garbage"), UUID.randomUUID());

        verifyNoInteractions(taskService);
        verify(sender).answerCallback(eq("cb1"), anyString());
    }

    private static TelegramCallbackQuery callback(String data) {
        var user = new TelegramUser(1L, "user", "Name", null);
        var message = new TelegramMessage(1L, user, new TelegramChat(100L), null, null);
        return new TelegramCallbackQuery("cb1", user, message, data);
    }
}