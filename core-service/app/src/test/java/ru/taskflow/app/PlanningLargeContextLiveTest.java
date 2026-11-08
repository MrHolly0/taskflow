package ru.taskflow.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.application.*;
import ru.taskflow.assistant.application.planning.*;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Tag("live")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.telegram.bot-token=", "app.public-base-url="})
@Import(PlanningComparisonLiveTest.LiveConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanningLargeContextLiveTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired AgentLoop direct;
    @Autowired ExperimentalAgentLoop experimental;
    @Autowired ContextBuilder contexts;
    @Autowired TaskService tasks;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlanningComparisonLiveTest.LiveGateway gateway;
    @Autowired PlanningGenerationSettings generation;
    @MockBean AssistantRateLimiter limiter;

    record Fixture(UUID user, UUID other, Map<String, UUID> ids, Map<String, Integer> ranks,
                   List<Map<String, Object>> snapshot) {}
    record Measurement(String scenario, String category, int taskCount, int repeat, PlanningMode mode,
                       boolean correct, int expected, int actual, int matched,
                       int dateChecks, int datesCorrect, int reminderChecks, int remindersCorrect,
                       int inputTokens, int outputTokens, int httpCalls, int passes, long elapsedMs,
                       boolean apiFailure, boolean noWrites, Map<String, Integer> targetRanks,
                       List<String> errors, List<ru.taskflow.assistant.api.dto.ProposedAction> actions) {}

    @Test void compareLargeAccountsWithoutApplyingProposals() throws Exception {
        assumeTrue("true".equals(System.getenv("PLANNING_LIVE_APPROVED")));
        assumeTrue(System.getenv("GROQ_API_KEY") != null && !System.getenv("GROQ_API_KEY").isBlank());
        assumeTrue(System.getenv("PLANNING_LIVE_BUDGET_FILE") != null);
        when(limiter.allow(any())).thenReturn(true);
        LocalDate day = LocalDate.now(PlanningLargeContextCases.ZONE);
        var selected = PlanningLargeContextCases.all(day);
        String requested = env("PLANNING_CASE_IDS", "");
        if (!requested.isBlank()) {
            var ids = Set.of(requested.split(","));
            selected = selected.stream().filter(s -> ids.contains(s.id())).toList();
            assertThat(selected).hasSize(ids.size());
        }
        assertThat(selected).isNotEmpty();
        List<Integer> sizes = Arrays.stream(env("PLANNING_TASK_COUNTS", "100,200").split(","))
                .map(Integer::parseInt).distinct().sorted().toList();
        assertThat(sizes).allMatch(n -> n == 100 || n == 200);
        List<PlanningMode> modes = Arrays.stream(env("PLANNING_MODES", "DIRECT,BATCH_PLAN,CODE_MODE").split(","))
                .map(PlanningMode::valueOf).toList();
        int repeats = Integer.parseInt(env("PLANNING_REPEATS", "1"));
        assertThat(repeats).isBetween(1, 3);
        String name = env("PLANNING_RUN_NAME", "large-context");assertThat(name).matches("[A-Za-z0-9_-]+");
        Path output = Path.of("build/reports/planning-" + name + ".json");
        Files.createDirectories(output.getParent());
        Path progress = output.resolveSibling("planning-" + name + ".jsonl");Files.writeString(progress, "");
        List<Measurement> results = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        var canonical = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        List<Map<String, Object>> definition = new ArrayList<>();
        for (var scenario : selected) {
            Map<String, Object> item = new TreeMap<>();
            item.put("id", scenario.id());item.put("request", scenario.request());item.put("expected", scenario.expected());
            item.put("control", scenario.control());item.put("hidden", new TreeSet<>(scenario.hidden()));item.put("visible", new TreeSet<>(scenario.visible()));
            item.put("seeds", PlanningLargeContextCases.seeds(day, scenario));
            item.put("background", java.util.stream.IntStream.range(0, 200).mapToObj(i -> PlanningLargeContextCases.background(day, scenario, i)).toList());
            definition.add(item);
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(definition)));
        int planned = sizes.size() * selected.size() * modes.size() * repeats;
        boolean stopped = false;
        int order = 0;
        outer: for (int repeat = 1; repeat <= repeats; repeat++) for (int count : sizes)
            for (var scenario : selected) {
                if (!LocalDate.now(PlanningLargeContextCases.ZONE).equals(day)) throw new IllegalStateException("DATE_CHANGED");
                List<PlanningMode> rotated = new ArrayList<>(modes);Collections.rotate(rotated, order++ % modes.size());
                for (PlanningMode mode : rotated) {
                    if (gateway.attempts.get() >= gateway.callLimit) { stopped = true;break outer; }
                    Fixture fixture = fixture(count, day, scenario);
                    int beforeCalls = gateway.attempts.get(), beforeErrors = gateway.errors.size(), beforeTraces = gateway.responses.size();
                    long start = System.nanoTime();AgentOutcome outcome = null;String exception = null;
                    try {
                        outcome = mode == PlanningMode.DIRECT
                                ? direct.run(fixture.user(), scenario.request(), PlanningLargeContextCases.ZONE, AssistantEntryPoint.CHAT)
                                : experimental.run(mode, fixture.user(), scenario.request(), PlanningLargeContextCases.ZONE, AssistantEntryPoint.CHAT);
                    } catch (Exception e) { exception = e.getClass().getSimpleName(); }
                    long elapsed = (System.nanoTime() - start) / 1000000;
                    boolean noWrites = fixture.snapshot().equals(snapshot(fixture.user(), fixture.other()));
                    boolean apiFailure = gateway.errors.size() > beforeErrors || outcome != null && outcome.llmFailed();
                    PlanningLargeContextScorer.Score score = outcome == null
                            ? new PlanningLargeContextScorer.Score(false, scenario.expected().size(), 0, 0,
                                (int) scenario.expected().stream().flatMap(e -> e.fields().keySet().stream()).filter(f -> f.equals("deadline") || f.equals("new_deadline")).count(), 0,
                                (int) scenario.expected().stream().filter(e -> e.fields().containsKey("reminder_at")).count(), 0, List.of("EXCEPTION:" + exception))
                            : PlanningLargeContextScorer.score(scenario, fixture.ids(), outcome, apiFailure);
                    List<String> errors = new ArrayList<>(score.errors());if (!noWrites) errors.add("UNEXPECTED_DATABASE_WRITE");
                    results.add(new Measurement(scenario.id(), scenario.category(), count, repeat, mode,
                            score.correct() && noWrites, score.expected(), score.actual(), score.matched(),
                            score.dateChecks(), score.datesCorrect(), score.reminderChecks(), score.remindersCorrect(),
                            outcome == null ? 0 : outcome.inputTokens(), outcome == null ? 0 : outcome.outputTokens(),
                            gateway.attempts.get() - beforeCalls, outcome == null ? 0 : outcome.passes(), elapsed,
                            apiFailure, noWrites, fixture.ranks(), List.copyOf(errors), outcome == null ? List.of() : outcome.actions()));
                    traces.add(Map.of("scenario", scenario.id(), "taskCount", count, "repeat", repeat, "mode", mode,
                            "responses", List.copyOf(gateway.responses.subList(beforeTraces, gateway.responses.size()))));
                    Files.writeString(progress, json.writeValueAsString(results.getLast()) + "\n", StandardOpenOption.APPEND);
                    write(output, hash, day, planned, stopped, sizes, repeats, selected, results, traces);
                    System.out.printf(Locale.ROOT, "LARGE %d %s %s repeat=%d correct=%s tokens=%d+%d%n", count,
                            scenario.id(), mode, repeat, score.correct() && noWrites, outcome == null ? 0 : outcome.inputTokens(), outcome == null ? 0 : outcome.outputTokens());
                    assertThat(noWrites).as("planning must not write tasks or reminder schedules").isTrue();
                    if (gateway.errors.stream().skip(beforeErrors).anyMatch("BUDGET_OR_APPROVAL"::equals)) { stopped = true;break outer; }
                    if (gateway.errors.stream().skip(beforeErrors).anyMatch(e -> e.startsWith("HTTP_429"))) Thread.sleep(20000);
                    Thread.sleep(Long.parseLong(env("PLANNING_PACING_MS", "500")));
                }
            }
        write(output, hash, day, planned, stopped, sizes, repeats, selected, results, traces);
        assertThat(results).isNotEmpty();
    }

    Fixture fixture(int count, LocalDate day, PlanningLargeContextCases.Scenario scenario) {
        UUID user = user(), other = user();Map<String, UUID> ids = new LinkedHashMap<>();
        var seeds = PlanningLargeContextCases.seeds(day, scenario);
        for (var seed : seeds) ids.put(seed.key(), create(user, seed).id());
        for (int i = 0; i < count - seeds.size(); i++) create(user, PlanningLargeContextCases.background(day, scenario, i));
        create(other, new PlanningLargeContextCases.Seed("foreign", "Проверить смету по проекту Янтарь", "Север", TaskPriority.MEDIUM,
                PlanningLargeContextCases.at(day, -2, 10, 0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?", Integer.class, user)).isEqualTo(count);
        var ranked = tasks.findAssistantContext(user, count);
        Map<UUID, Integer> ranks = new HashMap<>();for (int i = 0; i < ranked.size(); i++) ranks.put(ranked.get(i).id(), i + 1);
        var window = contexts.build(user);
        for (String key : scenario.hidden()) {
            assertThat(ranks.get(ids.get(key))).as(key + " outside DIRECT window").isGreaterThan(ContextBuilder.WINDOW_SIZE);
            assertThat(window.refs()).doesNotContainValue(ids.get(key));
        }
        for (String key : scenario.visible()) assertThat(ranks.get(ids.get(key))).isBetween(1, 12);
        Map<String, Integer> selectedRanks = new TreeMap<>();
        scenario.expected().stream().map(PlanningLargeContextCases.Expected::target).filter(Objects::nonNull)
                .forEach(key -> selectedRanks.put(key, ranks.get(ids.get(key))));
        return new Fixture(user, other, Map.copyOf(ids), Map.copyOf(selectedRanks), snapshot(user, other));
    }
    UUID user() {
        long telegram = ThreadLocalRandom.current().nextLong(1000000000L, 9000000000L);
        return users.findOrCreateByTelegram(telegram, "large_eval", "Test", "User").id();
    }
    TaskResponse create(UUID user, PlanningLargeContextCases.Seed seed) {
        return tasks.create(user, new CreateTaskRequest(seed.title(), null, seed.priority(), seed.deadline(), null,
                seed.group(), List.of(), null, TaskSource.MANUAL));
    }
    List<Map<String, Object>> snapshot(UUID user, UUID other) {
        var rows = new ArrayList<>(jdbc.queryForList("SELECT * FROM tasks WHERE user_id IN (?,?) ORDER BY id", user, other));
        rows.addAll(jdbc.queryForList("SELECT * FROM scheduled_notifications WHERE user_id IN (?,?) ORDER BY id", user, other));
        return rows;
    }
    void write(Path path, String hash, LocalDate day, int planned, boolean stopped, List<Integer> sizes, int repeats,
               List<PlanningLargeContextCases.Scenario> selected, List<Measurement> results, List<Map<String, Object>> traces) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluationKind", "large-context");report.put("model", gateway.model);report.put("anchorDate", day);
        report.put("taskCounts", sizes);report.put("scenarios", selected.size());report.put("repeats", repeats);
        report.put("datasetSha256", hash);report.put("plannedMeasurements", planned);report.put("stoppedByBudget", stopped);
        report.put("modelParameters", Map.of("temperature", .1, "maxCompletionTokens", 3000,
                "experimentalReasoningEffort", Objects.toString(generation.reasoningEffort(0), "provider-default"),
                "experimentalRepairEffort", Objects.toString(generation.reasoningEffort(1), "provider-default")));
        report.put("scoring", "strict one-to-one type and UUID matching; deadlines/reminders by instant to minute; unexpected mutable fields rejected; explicit control outcomes; no database writes");
        var digest = MessageDigest.getInstance("SHA-256");
        for (Class<?> type : List.of(AgentLoop.class, ExperimentalAgentLoop.class, PlanningPromptBuilder.class, PlanningSession.class,
                PlanningGenerationSettings.class, CodeModeExecutor.class, ru.taskflow.nlp.api.LlmToolRequest.class))
            try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) { digest.update(bytes.readAllBytes()); }
        report.put("plannerBytecodeSha256", HexFormat.of().formatHex(digest.digest()));
        report.put("httpAttempts", gateway.attempts.get());report.put("apiErrors", gateway.errors);
        report.put("measurements", results);report.put("traces", traces);
        report.put("requests", selected.stream().map(s -> Map.of("id", s.id(), "category", s.category(), "request", s.request(), "control", s.control())).toList());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
