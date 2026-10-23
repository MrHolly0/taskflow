package ru.taskflow.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.user.api.UserService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("load")
@AutoConfigureObservability(tracing = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.telegram.bot-token=", "app.telegram.miniapp-url=", "app.public-base-url=",
        "app.geoip.database-path=", "app.ucaller.service-id=", "app.ucaller.secret-key=",
        "app.jwt.secret=isolated_load_test_signing_key_at_least_32_characters",
        "server.tomcat.threads.max=24", "spring.datasource.hikari.maximum-pool-size=8",
        "logging.level.root=WARN"
})
@Testcontainers(disabledWithoutDocker = true)
class StandLoadIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("app", "munin-load-test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379).withLabel("app", "munin-load-test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @LocalServerPort private int port;
    @Autowired private UserService users;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate counters;
    @MockBean private NlpGatewayService nlp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

    @AfterEach
    void closeHttpClient() {
        http.close();
    }

    @Test
    void oneHundredUsersCreateRetryAndReadThroughRealHttpSecurity() throws Exception {
        List<SyntheticUser> identities = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var user = users.findOrCreateByTelegram(8_800_000_000L + i,
                    "load_user_" + i, "Проверка", null);
            identities.add(new SyntheticUser(user.id(), jwt.issueAccessToken(user.id(), "load_user_" + i),
                    "Синтетическая задача " + i));
        }

        long started = System.nanoTime();
        List<CreatedTask> created;
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<CreatedTask>> writes = identities.stream().<Callable<CreatedTask>>map(user -> () -> {
                String body = objectMapper.writeValueAsString(java.util.Map.of("title", user.title()));
                // Одинаковый ключ у разных пользователей также проверяет область дедупликации.
                JsonNode first = request(user, "/api/v1/tasks", body, 201);
                JsonNode repeated = request(user, "/api/v1/tasks", body, 201);
                assertThat(repeated.get("id").asText()).isEqualTo(first.get("id").asText());
                return new CreatedTask(user, UUID.fromString(first.get("id").asText()));
            }).toList();
            created = results(executor.invokeAll(writes, 90, TimeUnit.SECONDS));

            List<Callable<Boolean>> reads = new ArrayList<>();
            for (int i = 0; i < created.size(); i++) {
                CreatedTask own = created.get(i);
                UUID foreign = created.get((i + 1) % created.size()).taskId();
                reads.add(() -> {
                    JsonNode page = request(own.user(), "/api/v1/tasks/view?size=1&page=0", null, 200);
                    assertThat(page.get("totalElements").asInt()).isEqualTo(1);
                    assertThat(page.get("size").asInt()).isEqualTo(1);
                    assertThat(page.get("content").size()).isEqualTo(1);
                    assertThat(page.get("content").get(0).get("id").asText()).isEqualTo(own.taskId().toString());
                    assertThat(page.get("content").get(0).get("title").asText()).isEqualTo(own.user().title());
                    JsonNode next = request(own.user(), "/api/v1/tasks/view?size=1&page=1", null, 200);
                    assertThat(next.get("content").isEmpty()).isTrue();
                    request(own.user(), "/api/v1/tasks/" + foreign, null, 404);
                    return true;
                });
            }
            results(executor.invokeAll(reads, 90, TimeUnit.SECONDS));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isEqualTo(100);
        assertThat(jdbc.queryForList("SELECT COUNT(*) AS n FROM tasks GROUP BY user_id", Integer.class))
                .hasSize(100).containsOnly(1);
        SyntheticUser sample = identities.getFirst();
        assertThat(requestCount(sample.id(), "write")).isEqualTo(2);
        assertThat(requestCount(sample.id(), "read")).isEqualTo(3);
        verifyNoInteractions(nlp);

        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        verifyMetricsEndpoint();
        verifyBackupRestore();

        List<Long> measured = latencies.stream().sorted().toList();
        assertThat(measured).hasSize(500);
        System.out.printf(java.util.Locale.ROOT,
                "Synthetic HTTP check: users=100 concurrency=12 requests=%d elapsed=%.2fs p50=%.1fms p95=%.1fms max=%.1fms%n",
                measured.size(), elapsed, millis(measured, 0.50), millis(measured, 0.95),
                measured.getLast() / 1_000_000.0);
    }

    private void verifyMetricsEndpoint() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("http_server_requests_seconds_count", "telegram_inbox_pending");
    }

    private void verifyBackupRestore() throws Exception {
        assertThat(postgres.execInContainer("pg_dump", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                "-Fc", "-f", "/tmp/munin-check.dump").getExitCode()).as("create test backup").isZero();
        assertThat(postgres.execInContainer("createdb", "-U", postgres.getUsername(), "munin_restore_check")
                .getExitCode()).as("create isolated restore database").isZero();
        assertThat(postgres.execInContainer("pg_restore", "-U", postgres.getUsername(), "-d", "munin_restore_check",
                "--no-owner", "--no-privileges", "/tmp/munin-check.dump").getExitCode())
                .as("restore test backup").isZero();
        var restored = postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", "munin_restore_check",
                "-tAc", "SELECT COUNT(*) FROM tasks");
        assertThat(restored.getExitCode()).isZero();
        assertThat(restored.getStdout().strip()).isEqualTo("100");
    }

    private JsonNode request(SyntheticUser user, String path, String body, int expectedStatus) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + user.token());
        if (body != null) {
            builder.header("Content-Type", "application/json").header("Idempotency-Key", "load-create-1")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else builder.GET();
        long started = System.nanoTime();
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        latencies.add(System.nanoTime() - started);
        assertThat(response.statusCode()).as("HTTP status for %s", path).isEqualTo(expectedStatus);
        return objectMapper.readTree(response.body());
    }

    private long requestCount(UUID user, String kind) {
        var keys = counters.keys("api:rate:" + user + ":" + kind + ":*");
        assertThat(keys).isNotEmpty();
        return counters.opsForValue().multiGet(keys).stream().mapToLong(Long::parseLong).sum();
    }

    private static <T> List<T> results(List<Future<T>> futures) throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) results.add(future.get(5, TimeUnit.SECONDS));
        return results;
    }

    private static double millis(List<Long> samples, double percentile) {
        int position = Math.max(0, (int) Math.ceil(samples.size() * percentile) - 1);
        return samples.get(position) / 1_000_000.0;
    }

    private record SyntheticUser(UUID id, String token, String title) {}
    private record CreatedTask(SyntheticUser user, UUID taskId) {}
}
