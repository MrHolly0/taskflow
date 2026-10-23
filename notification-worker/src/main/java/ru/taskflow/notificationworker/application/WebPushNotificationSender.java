package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Web Push — стандарт, не сервис: обращаемся напрямую к push-службе браузера
 * через VAPID, без Firebase и без платного посредника.
 */
@Service
@Slf4j
public class WebPushNotificationSender implements NotificationSender {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PushService pushService;

    public WebPushNotificationSender(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${app.push.vapid-public-key:}") String vapidPublicKey,
            @Value("${app.push.vapid-private-key:}") String vapidPrivateKey,
            @Value("${app.push.vapid-subject:}") String vapidSubject
    ) throws GeneralSecurityException {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        Security.addProvider(new BouncyCastleProvider());
        if (vapidPublicKey.isBlank() || vapidPrivateKey.isBlank()) {
            log.error("VAPID-ключи не заданы — push-уведомления отправляться не будут");
            this.pushService = null;
        } else {
            this.pushService = new PushService(vapidPublicKey, vapidPrivateKey, vapidSubject);
        }
    }

    @Override
    public boolean supports(String channel) {
        return "WEB_PUSH".equals(channel);
    }

    @Override
    public void sendTaskReminder(String destination, String title, String deadline, String timezone) {
        if (pushService == null) {
            throw new IllegalStateException("VAPID-ключи не настроены");
        }

        Subscription subscription = findSubscription(destination);
        String payload = buildPayload(title, deadline, timezone);

        HttpResponse response;
        Future<HttpResponse> request = null;
        try {
            request = pushService.sendAsync(
                    new Notification(subscription.endpoint(), subscription.p256dh(), subscription.auth(), payload));
            response = request.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (request != null) {
                request.cancel(true);
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Не удалось отправить push-уведомление", e);
        }

        int status = response.getStatusLine().getStatusCode();
        // 404/410 — endpoint отозван самим браузером/ОС, повтор бессмыслен:
        // сигнал удалить подписку, а не ошибка очереди.
        if (status == 404 || status == 410) {
            throw new PushSubscriptionGoneException(destination);
        }
        if (status >= 300) {
            var retryHeader = response.getFirstHeader("Retry-After");
            throw new NotificationDeliveryException("PUSH_HTTP_" + status,
                    status >= 400 && status < 500 && status != 408 && status != 429,
                    NotificationRetryPolicy.parseRetryAfter(retryHeader == null ? null : retryHeader.getValue()));
        }
        // 2xx здесь означает «служба приняла запрос в очередь на доставку», не
        // «показано на устройстве» — та же ловушка, что и call_status: 1 у
        // Ucaller: успех на уровне протокола приняли за факт доставки. Кто
        // отвечает дальше, видно по домену endpoint — сам endpoint в лог не
        // идёт, это фактически предъявительский токен на устройство.
        log.info("Push-служба {} приняла уведомление для подписки {} — это подтверждение приёма " +
                "в очередь, не показа на устройстве", PushServiceIdentifier.nameFor(subscription.endpoint()), destination);
    }

    private record Subscription(String endpoint, String p256dh, String auth) {}

    private Subscription findSubscription(String destination) {
        String sql = "SELECT endpoint, p256dh, auth FROM push_subscriptions WHERE id = ?";
        List<Subscription> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> new Subscription(rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth")),
                UUID.fromString(destination));
        if (rows.isEmpty()) {
            // Подписку уже отвязали (например, юзер отключил уведомления между
            // постановкой в очередь и отправкой) — то же самое протухание.
            throw new PushSubscriptionGoneException(destination);
        }
        return rows.get(0);
    }

    private String buildPayload(String title, String deadline, String timezone) {
        String humanized = DeadlineHumanizer.humanize(deadline, timezone);
        String body = humanized == null ? "Напоминание о задаче" : "Срок: " + humanized;
        try {
            return objectMapper.writeValueAsString(Map.of("title", title, "body", body));
        } catch (Exception e) {
            log.warn("Failed to serialize push payload, falling back to plain title: {}", e.getMessage());
            return "{\"title\":\"" + title.replace("\"", "'") + "\"}";
        }
    }
}
