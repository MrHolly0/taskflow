package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramNotificationSender {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Value("${app.telegram.api-base-url:https://api.telegram.org}")
    private String apiBaseUrl;

    public void sendTaskReminder(Long chatId, String title, String deadline) {
        String text = String.format("📌 Напоминание о задаче\n\n<b>%s</b>\nДедлайн: %s", title, deadline);

        try {
            sendMessage(chatId, text);
            log.info("Sent notification to chat {}", chatId);
        } catch (RestClientException e) {
            log.error("Failed to send notification to chat {}: {}", chatId, e.getMessage());
            throw e;
        }
    }

    private void sendMessage(Long chatId, String text) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("parse_mode", "HTML");

        String url = apiBaseUrl + "/bot" + botToken + "/sendMessage";

        restClient.post()
            .uri(url)
            .body(payload)
            .retrieve()
            .toEntity(String.class);
    }
}
