package ru.taskflow.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.telegram.application.UpdateIdempotencyService;
import ru.taskflow.telegram.application.UpdateRouter;
import ru.taskflow.telegram.infrastructure.web.TelegramWebhookController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TelegramWebhookControllerTest {

    @Mock
    private UpdateIdempotencyService idempotencyService;

    @Mock
    private UpdateRouter updateRouter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new TelegramWebhookController("test-secret", idempotencyService, updateRouter);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void correctSecret_returns200_andRoutesUpdate() throws Exception {
        when(idempotencyService.isAlreadyProcessed(anyLong())).thenReturn(false);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(1L)))
                .andExpect(status().isOk());

        verify(updateRouter).route(any());
    }

    @Test
    void wrongSecret_returns403() throws Exception {
        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(2L)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(updateRouter, idempotencyService);
    }

    @Test
    void duplicateUpdate_returns200_withoutRouting() throws Exception {
        when(idempotencyService.isAlreadyProcessed(3L)).thenReturn(true);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(3L)))
                .andExpect(status().isOk());

        verifyNoInteractions(updateRouter);
    }

    private static String updateJson(long updateId) {
        return """
                {"update_id":%d,"message":{"message_id":1,"from":{"id":1,"first_name":"Test"},"chat":{"id":1},"text":"hi"}}
                """.formatted(updateId);
    }
}