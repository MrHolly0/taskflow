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
public class TelegramNotificationSender implements NotificationSender {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Value("${app.telegram.api-base-url:https://api.telegram.org}")
    private String apiBaseUrl;

    @Override
    public boolean supports(String channel) {
        return "TELEGRAM".equals(channel);
    }

    @Override
    public void sendTaskReminder(String destination, String title, String deadline, String timezone) {
        Long chatId = Long.parseLong(destination);
        String text = buildMessageText(title, deadline, timezone);

        try {
            sendMessage(chatId, text);
            log.info("Sent notification to chat {}", chatId);
        } catch (RestClientException e) {
            log.error("Failed to send notification to chat {}: {}", chatId, e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * Собирает текст напоминания. Отдельная точка входа для тестов —
     * не требует поднятия RestClient ради проверки чистой логики форматирования.
     */
    String buildMessageText(String title, String deadline, String timezone) {
        return "📌 Напоминание о задаче\n\n<b>" + escape(title) + "</b>" + deadlineLine(deadline, timezone);
    }

    private String deadlineLine(String iso, String timezone) {
        String humanized = DeadlineHumanizer.humanize(iso, timezone);
        return humanized == null ? "" : "\nДедлайн: " + humanized;
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
            .onStatus(status -> status.isError(), (request, response) -> {
                int status = response.getStatusCode().value();
                java.time.Duration retryAfter = NotificationRetryPolicy.parseRetryAfter(
                        response.getHeaders().getFirst("Retry-After"));
                if (status == 429) {
                    try {
                        long seconds = objectMapper.readTree(response.getBody())
                                .path("parameters").path("retry_after").asLong(0);
                        if (seconds > 0) {
                            retryAfter = java.time.Duration.ofSeconds(Math.min(seconds, 604800));
                        }
                    } catch (Exception ignored) {
                        // При отсутствии JSON остаётся Retry-After или обычная задержка очереди.
                    }
                }
                throw new NotificationDeliveryException("TELEGRAM_HTTP_" + status,
                        status >= 400 && status < 500 && status != 408 && status != 429, retryAfter);
            })
            .toEntity(String.class);
    }
}
