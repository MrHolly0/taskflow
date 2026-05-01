package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
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

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DEADLINE_FMT =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm", new Locale("ru")).withZone(MOSCOW);

    public void sendTaskReminder(Long chatId, String title, String deadline) {
        String deadlineFormatted = formatDeadline(deadline);
        String text = String.format("📌 Напоминание о задаче\n\n<b>%s</b>\nДедлайн: %s", title, deadlineFormatted);

        try {
            sendMessage(chatId, text);
            log.info("Sent notification to chat {}", chatId);
        } catch (RestClientException e) {
            log.error("Failed to send notification to chat {}: {}", chatId, e.getMessage());
            throw e;
        }
    }

    private String formatDeadline(String iso) {
        if (iso == null || iso.isBlank()) return "не указан";
        try {
            return DEADLINE_FMT.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso;
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
