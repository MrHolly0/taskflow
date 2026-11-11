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
import java.util.List;

@Service
@Slf4j
public class TelegramNotificationSender implements NotificationSender {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WidgetDelivery widgets;
    @org.springframework.beans.factory.annotation.Autowired
    public TelegramNotificationSender(RestClient client,ObjectMapper mapper,WidgetDelivery widgets) {
        this.restClient=client;this.objectMapper=mapper;this.widgets=widgets;
    }
    public TelegramNotificationSender(RestClient client,ObjectMapper mapper) { this(client,mapper,null); }
    @Override
    public void sendTaskReminderCard(ru.taskflow.notificationworker.infrastructure.db.PendingNotification notification,
            String title,String deadline,String timezone) {
        if (!widgets.send(notification)) sendTaskReminder(notification.destination(),title,deadline,timezone);
    }
    @Override
    public void sendEngagement(ru.taskflow.notificationworker.infrastructure.db.PendingNotification notification,EngagementPayload payload) {
        if (!widgets.send(notification)) sendEngagement(notification.destination(),notification.payloadType(),payload);
    }
    @Override
    public void sendWidgetSync(ru.taskflow.notificationworker.infrastructure.db.PendingNotification notification) { widgets.sync(notification); }


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

    @Override
    public void sendEngagement(String destination, String kind, EngagementPayload payload) {
        String prefix = "eng:" + payload.promptId() + ":";
        var rows = new java.util.ArrayList<List<Map<String, String>>>();
        String text;
        if ("TASK_CHECKIN".equals(kind) || "WORK_CHECKIN".equals(kind)) {
            text = "Как продвигается дело?\n\n<b>" + escape(payload.taskTitle())
                    + "</b>" + deadlineLine(payload.deadline(), payload.timezone()) + "\n\nПолучилось завершить?";
            rows.add(List.of(Map.of("text", "✅ Готово", "callback_data", prefix + "done"),
                    Map.of("text", "⏳ Ещё в работе", "callback_data", prefix + "working")));
            rows.add(List.of(Map.of("text", "🔔 Напомнить через час", "callback_data", prefix + "later")));
        } else {
            text = "Как твои дела? Если появилось новое, можно рассказать Мунину — текстом или голосом. Разберём вместе.";
        }
        if (payload.appUrl() != null && !payload.appUrl().isBlank()) {
            String url = payload.appUrl().replaceAll("/+$", "");
            if (payload.taskId() != null) url += "/?task=" + payload.taskId();
            rows.add(List.of(Map.of("text", "Открыть Мунин", "url", url)));
        }
        if ("RETURN_NUDGE".equals(kind)) rows.add(List.of(
                Map.of("text", "Не напоминать об этом", "callback_data", prefix + "mute")));
        sendMessage(Long.parseLong(destination), text, Map.of("inline_keyboard", rows));
    }

    private void sendMessage(Long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    private void sendMessage(Long chatId, String text, Map<String, Object> keyboard) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("parse_mode", "HTML");
        if (keyboard != null) payload.put("reply_markup", keyboard);

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
