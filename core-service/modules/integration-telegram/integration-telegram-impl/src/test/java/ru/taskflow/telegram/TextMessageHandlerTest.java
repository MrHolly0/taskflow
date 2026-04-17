package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.telegram.application.TextMessageHandler;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextMessageHandlerTest {

    @Mock
    private TaskService taskService;

    @Mock
    private TelegramMessageSender sender;

    @InjectMocks
    private TextMessageHandler handler;

    @Test
    void plainText_createsTask() {
        var userId = UUID.randomUUID();
        var message = message("купить молоко");
        var response = taskResponse(UUID.randomUUID(), "купить молоко");
        when(taskService.create(eq(userId), any(CreateTaskRequest.class))).thenReturn(response);

        handler.handle(message, userId);

        var captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskService).create(eq(userId), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("купить молоко");
    }

    @Test
    void plainText_sendsConfirmation() {
        var userId = UUID.randomUUID();
        var taskId = UUID.randomUUID();
        var message = message("купить молоко");
        when(taskService.create(eq(userId), any(CreateTaskRequest.class))).thenReturn(taskResponse(taskId, "купить молоко"));

        handler.handle(message, userId);

        verify(sender).sendMessage(eq(100L), any(String.class), any());
    }

    private static TelegramMessage message(String text) {
        return new TelegramMessage(1L, new TelegramUser(1L, "user", "Name", null),
                new TelegramChat(100L), text, null);
    }

    private static TaskResponse taskResponse(UUID id, String title) {
        return new TaskResponse(id, title, null, null, null, null, null, false, null, null, null, null, null, null, null);
    }
}