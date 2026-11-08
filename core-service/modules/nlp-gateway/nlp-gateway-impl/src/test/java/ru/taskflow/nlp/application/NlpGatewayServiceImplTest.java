package ru.taskflow.nlp.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.nlp.api.LlmMessage;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.infrastructure.NlpGatewayConfig;
import ru.taskflow.nlp.infrastructure.NlpWorkerClient;
import ru.taskflow.nlp.infrastructure.RestClientConfig;

import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NlpGatewayServiceImplTest {

    /**
     * Реальный сокет, который принимает соединение и никогда не отвечает —
     * читающий таймаут клиента обязан сработать раньше, чем сервер что-то
     * пришлёт. Таймаут в тесте короче общего дедлайна операции только ради
     * скорости прогона — сама проверка честная: настоящий сетевой таймаут,
     * а не имитация.
     */
    @Test
    void callWithTools_returnsUnavailable_whenToolCallTimesOut() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread unresponsiveServer = new Thread(() -> {
                try (var socket = serverSocket.accept()) {
                    Thread.sleep(5000);
                } catch (Exception ignored) {
                    // сокет закроется вместе с клиентским таймаутом — ожидаемо
                }
            });
            unresponsiveServer.setDaemon(true);
            unresponsiveServer.start();

            var config = new NlpGatewayConfig();
            config.setWorkerUrl("http://localhost:" + serverSocket.getLocalPort());

            var policy=new ru.taskflow.shared.resources.ResourcePolicy();
            policy.setOperationTimeoutMs(200);policy.setInternalSecret("x".repeat(32));
            var restClientConfig = new RestClientConfig(policy);
            try(var scope=ru.taskflow.shared.resources.ModelRequestContext.open(
                    new ru.taskflow.shared.resources.ModelRequestContext(java.util.UUID.randomUUID(),System.currentTimeMillis()+200,0))) {
            var client = new NlpWorkerClient(config, restClientConfig.restClient(), restClientConfig.toolCallRestClient());
            var gateway = new NlpGatewayServiceImpl(client);

            var response = gateway.callWithTools(new LlmToolRequest(List.of(LlmMessage.user("привет")), List.of()));

            assertThat(response.failed()).isTrue();
            assertThat(response.toolCalls()).isEmpty();
            }
        }
    }

    @Test void quotaRejectionReachesCallerWithoutDegradationOrRetry() {
        var policy=new ru.taskflow.shared.resources.ResourcePolicy();policy.setInternalSecret("s".repeat(32));
        var builder=org.springframework.web.client.RestClient.builder().requestInterceptor(
                new ru.taskflow.shared.resources.InternalWorkerInterceptor(policy));
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://localhost:8081/nlp/tool-call"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .header("X-Resource-Rejected","true").header("Retry-After","80000"));
        var http=builder.build();var client=new NlpWorkerClient(new NlpGatewayConfig(),http,http);
        try(var scope=ru.taskflow.shared.resources.ModelRequestContext.open(new ru.taskflow.shared.resources.ModelRequestContext(
                java.util.UUID.randomUUID(),System.currentTimeMillis()+5000,0))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->new NlpGatewayServiceImpl(client)
                    .callWithTools(new LlmToolRequest(List.of(LlmMessage.user("дело")),List.of())))
                    .isInstanceOfSatisfying(ru.taskflow.shared.resources.ResourceRejectedException.class,e->{
                        assertThat(e.getStatusCode().value()).isEqualTo(429);assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("80000");
                    });
        }
        server.verify();
    }
}
