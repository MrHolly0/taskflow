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

    @Test
    void checkinHasBoundedOwnerTokenButtonsAndEscapedTitle() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = sender(builder);
        var token = java.util.UUID.randomUUID();
        var task = java.util.UUID.randomUUID();
        server.expect(requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("a &lt; b &amp; c")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.reply_markup.inline_keyboard[0][0].callback_data").value("eng:" + token + ":done"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.reply_markup.inline_keyboard[1][0].callback_data").value("eng:" + token + ":later"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.reply_markup.inline_keyboard[2][0].url").value("https://example.invalid/?task=" + task))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        sender.sendEngagement("12345", "TASK_CHECKIN", new EngagementPayload(token, task, "a < b & c", null,
                "Europe/Moscow", "https://example.invalid/"));
        assertThat(("eng:" + token + ":working").getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(64);
        server.verify();
    }

    @Test
    void returnMessageHasOpenAndSeparateUnsubscribeButton() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = sender(builder);
        var token = java.util.UUID.randomUUID();
        server.expect(requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.reply_markup.inline_keyboard[0][0].url").value("https://example.invalid"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.reply_markup.inline_keyboard[1][0].callback_data").value("eng:" + token + ":mute"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        sender.sendEngagement("12345", "RETURN_NUDGE", new EngagementPayload(token, null, null, null,
                "Europe/Moscow", "https://example.invalid/"));
        server.verify();
    }

    private TelegramNotificationSender sender(RestClient.Builder builder) {
        var sender = new TelegramNotificationSender(builder.build(), new ObjectMapper());
        ReflectionTestUtils.setField(sender, "botToken", "test-token");
        ReflectionTestUtils.setField(sender, "apiBaseUrl", "https://api.telegram.org");
        return sender;
    }
}
