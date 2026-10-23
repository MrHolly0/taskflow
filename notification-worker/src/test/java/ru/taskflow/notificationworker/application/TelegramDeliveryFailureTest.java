package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class TelegramDeliveryFailureTest {

    @Test
    void telegramRateLimitReadsDelayFromResponseBody() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = sender(builder);
        server.expect(requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"parameters\":{\"retry_after\":90}}"));

        var failure = catchThrowableOfType(() -> sender.sendTaskReminder("12345", "задача", null, "Europe/Moscow"),
                NotificationDeliveryException.class);

        assertThat(failure.permanent()).isFalse();
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(90));
        server.verify();
    }

    @Test
    void blockedBotDoesNotRetryAndPayloadEscapesMarkup() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = sender(builder);
        server.expect(requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        var failure = catchThrowableOfType(() -> sender.sendTaskReminder("12345", "a < b & c", null, "Europe/Moscow"),
                NotificationDeliveryException.class);

        assertThat(failure.permanent()).isTrue();
        assertThat(sender.buildMessageText("a < b & c", null, "Europe/Moscow"))
                .contains("a &lt; b &amp; c");
        server.verify();
    }

    private TelegramNotificationSender sender(RestClient.Builder builder) {
        var sender = new TelegramNotificationSender(builder.build(), new ObjectMapper());
        ReflectionTestUtils.setField(sender, "botToken", "test-token");
        ReflectionTestUtils.setField(sender, "apiBaseUrl", "https://api.telegram.org");
        return sender;
    }
}
