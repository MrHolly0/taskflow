package ru.taskflow.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.app.experiment.ActionMatcher;
import ru.taskflow.app.experiment.DatasetReader;
import ru.taskflow.app.experiment.DatasetRow;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.assistant.application.AgentLoop;
import ru.taskflow.assistant.application.AgentOutcome;
import ru.taskflow.assistant.application.planning.ExperimentalAgentLoop;
import ru.taskflow.assistant.application.planning.PlanningMode;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.user.api.UserService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("live")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.telegram.bot-token=", "app.public-base-url="})
@Import(PlanningComparisonLiveTest.LiveConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanningDatasetLiveTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired AgentLoop direct;
    @Autowired ExperimentalAgentLoop experimental;
    @Autowired TaskService tasks;
    @Autowired UserService users;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ru.taskflow.assistant.application.planning.PlanningGenerationSettings generation;
    @Autowired PlanningComparisonLiveTest.LiveGateway gateway;

    record Result(String datasetId, String category, int repeat, PlanningMode mode, boolean correct, boolean legacyCorrect,
                  boolean apiFailure, boolean noWrites, int expected, int actual, int matched, int missing, int extra,
                  int attributeMismatches, boolean expectedAmbiguous, boolean choiceOffered,
                  int inputTokens, int outputTokens, int httpCalls, int passes, long elapsedMs, long modelMs,
                  List<String> missingDescriptions, List<String> extraDescriptions,
                  List<String> attributeErrors, List<String> rejections, List<ProposedAction> actions) {}

    @Test void compareOriginalDatasetWithoutChangingItsLabels() throws Exception {
        assumeTrue("true".equals(System.getenv("PLANNING_LIVE_APPROVED")));
        assumeTrue(System.getenv("GROQ_API_KEY") != null && !System.getenv("GROQ_API_KEY").isBlank());
        assumeTrue(System.getenv("PLANNING_LIVE_BUDGET_FILE") != null, "Persistent budget required");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        assertThat(root).isNotNull();
        Path datasetFile = root.resolve(env("PLANNING_DATASET", "docs/vkr/dataset/nlp-dataset.json"));
        byte[] datasetBytes = Files.readAllBytes(datasetFile);
        String datasetHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datasetBytes));
        List<DatasetRow> all = DatasetReader.read(datasetFile);
        int perCategory = Integer.parseInt(env("PLANNING_PER_CATEGORY", "0"));
        Map<String, Integer> categoryCounts = new HashMap<>();
        List<DatasetRow> dataset = all.stream().filter(row -> perCategory <= 0
                || ("true".equals(env("PLANNING_ALL_NEGATIVE","false")) && "NEGATIVE".equals(row.category()))
                || categoryCounts.merge(row.category(), 1, Integer::sum) <= perCategory).toList();
        String requestedIds = env("PLANNING_CASE_IDS", "");
        if (!requestedIds.isBlank()) {
            var ids = Arrays.asList(requestedIds.split(","));
            dataset = dataset.stream().filter(row -> ids.contains(row.id())).toList();
        }
        assertThat(dataset).isNotEmpty();
        List<PlanningMode> configuredModes = Arrays.stream(env("PLANNING_MODES", "DIRECT,BATCH_PLAN,CODE_MODE").split(","))
                .map(PlanningMode::valueOf).toList();
        int repeats = Integer.parseInt(env("PLANNING_REPEATS", "1"));
        assertThat(repeats).isBetween(1, 3);
        long pacingMs = Long.parseLong(env("PLANNING_PACING_MS", "1200"));
        String runName = env("PLANNING_RUN_NAME", "dataset");
        assertThat(runName).matches("[a-zA-Z0-9_-]+");
        Path output = Path.of("build/reports/planning-" + runName + ".json");
        Path progress = Path.of("build/reports/planning-" + runName + ".jsonl");
        Files.createDirectories(output.getParent());
        Files.writeString(progress, "");
        List<Result> results = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        ActionMatcher matcher = new ActionMatcher();
        int rowIndex = 0;
        boolean stopped = false;
        outer: for (int repeat = 1; repeat <= repeats; repeat++) {
            for (DatasetRow row : dataset) {
                OffsetDateTime anchor = OffsetDateTime.now(ZONE).withNano(0);
                List<PlanningMode> modes = new ArrayList<>(configuredModes);
                Collections.rotate(modes, (rowIndex++ + repeat) % modes.size());
                for (PlanningMode mode : modes) {
                    if (gateway.attempts.get() >= gateway.callLimit) { stopped = true; break outer; }
                    long telegram = ThreadLocalRandom.current().nextLong(1000000000L, 9000000000L);
                    UUID user = users.findOrCreateByTelegram(telegram, "planning_eval", "Test", "User").id();
                    Map<String, UUID> targets = new HashMap<>();
                    Map<String, OffsetDateTime> deadlines = new HashMap<>();
                    for (var spec : row.setupTasks()) {
                        var task = tasks.create(user, new CreateTaskRequest(spec.title(), null,
                                spec.priority() == null ? null : TaskPriority.valueOf(spec.priority()),
                                spec.deadlineOffsetDays() == null ? null : anchor.plusDays(spec.deadlineOffsetDays()),
                                null, spec.group(), List.of(), null, TaskSource.MANUAL));
                        targets.put(spec.ref(), task.id());
                        if (task.deadline() != null) deadlines.put(spec.ref(), task.deadline());
                    }
                    int beforeCalls = gateway.attempts.get(), beforeErrors = gateway.errors.size(), beforeTraces = gateway.responses.size();
                    var beforeState = snapshot(user);
                    long start = System.nanoTime();
                    AgentOutcome out = mode == PlanningMode.DIRECT
                            ? direct.run(user, row.text(), ZONE, AssistantEntryPoint.CHAT)
                            : experimental.run(mode, user, row.text(), ZONE, AssistantEntryPoint.CHAT);
                    long elapsed = (System.nanoTime() - start) / 1000000;
                    boolean noWrites = beforeState.equals(snapshot(user));
                    var legacy = matcher.match(out.actions(), row.expected(), targets, deadlines, anchor.toLocalDate(), ZONE);
                    var match = matcher.match(PlanningResultScorer.normalizeTimes(out.actions(), ZONE),
                            row.expected(), targets, deadlines, anchor.toLocalDate(), ZONE);
                    boolean apiFailure = out.llmFailed() || gateway.errors.size() > beforeErrors;
                    boolean technicalFailure = out.rejections().stream().anyMatch(code -> code.matches("[A-Z_]+"));
                    boolean correct = noWrites && !apiFailure && !technicalFailure && out.inputTokens() > 0 && match.fullyCorrect()
                            && (!row.expected().ambiguous() || out.ambiguous());
                    var attributes = match.matched().stream().flatMap(pair -> pair.attributeMismatches().stream()).toList();
                    Result result = new Result(row.id(), row.category(), repeat, mode, correct,
                            noWrites && !apiFailure && !technicalFailure && out.inputTokens() > 0 && legacy.fullyCorrect()
                                    && (!row.expected().ambiguous() || out.ambiguous()), apiFailure, noWrites,
                            match.expectedCount(), match.actualCount(), match.matchedCount(), match.missingCount(), match.extraCount(),
                            attributes.size(), row.expected().ambiguous(), out.ambiguous(), out.inputTokens(), out.outputTokens(),
                            gateway.attempts.get() - beforeCalls, out.passes(), elapsed,
                            out.firstPassLatencyMs()+out.secondPassLatencyMs(), match.missingDescriptions(),
                            match.extraDescriptions(), attributes, out.rejections(), out.actions());
                    results.add(result);
                    traces.add(Map.of("datasetId", row.id(), "repeat", repeat, "mode", mode,
                            "responses", List.copyOf(gateway.responses.subList(beforeTraces, gateway.responses.size()))));
                    Files.writeString(progress, json.writeValueAsString(result) + "\n", java.nio.file.StandardOpenOption.APPEND);
                    if (results.size() % 10 == 0)
                        writeReport(output, datasetHash, dataset.size(), repeats, configuredModes.size(), stopped, results, traces);
                    System.out.printf(Locale.ROOT, "CASE %s %s correct=%s tokens=%d+%d calls=%d%n",
                            row.id(), mode, correct, out.inputTokens(), out.outputTokens(), result.httpCalls());
                    assertThat(noWrites).as("planning must not write tasks, reminders or notification schedules").isTrue();
                    if (gateway.errors.stream().skip(beforeErrors).anyMatch("BUDGET_OR_APPROVAL"::equals)) {
                        stopped = true;
                        break outer;
                    }
                    if (apiFailure && gateway.errors.stream().skip(beforeErrors).anyMatch(error -> error.startsWith("HTTP_429")))
                        Thread.sleep(20000);
                    Thread.sleep(pacingMs);
                }
            }
        }
        writeReport(output, datasetHash, dataset.size(), repeats, configuredModes.size(), stopped, results, traces);
        assertThat(Files.readAllBytes(datasetFile)).isEqualTo(datasetBytes);
        assertThat(results).isNotEmpty();
    }

    private List<Map<String,Object>> snapshot(UUID user) {
        var rows = new ArrayList<>(jdbc.queryForList("SELECT * FROM tasks WHERE user_id=? ORDER BY id",user));
        rows.addAll(jdbc.queryForList("SELECT r.* FROM reminders r JOIN tasks t ON t.id=r.task_id WHERE t.user_id=? ORDER BY r.id",user));
        rows.addAll(jdbc.queryForList("SELECT * FROM scheduled_notifications WHERE user_id=? ORDER BY id",user));
        return rows;
    }

    private void writeReport(Path path, String hash, int size, int repeats, int modeCount, boolean stopped,
                             List<Result> results, List<Map<String, Object>> traces) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (PlanningMode mode : PlanningMode.values()) {
            var rows = results.stream().filter(row -> row.mode() == mode).toList();
            if (rows.isEmpty()) continue;
            long correct = rows.stream().filter(Result::correct).count();
            int expected = rows.stream().mapToInt(Result::expected).sum();
            int actual = rows.stream().mapToInt(Result::actual).sum();
            int matched = rows.stream().mapToInt(Result::matched).sum();
            summary.put(mode.name(), Map.ofEntries(
                    Map.entry("rows", rows.size()), Map.entry("correct", correct),
                    Map.entry("fullAccuracy", (double) correct / rows.size()),
                    Map.entry("legacyFullAccuracy", (double) rows.stream().filter(Result::legacyCorrect).count() / rows.size()),
                    Map.entry("apiFailures", rows.stream().filter(Result::apiFailure).count()),
                    Map.entry("precision", actual == 0 ? 0 : (double) matched / actual),
                    Map.entry("recall", expected == 0 ? 0 : (double) matched / expected),
                    Map.entry("inputTokens", rows.stream().mapToInt(Result::inputTokens).sum()),
                    Map.entry("outputTokens", rows.stream().mapToInt(Result::outputTokens).sum()),
                    Map.entry("avgMs", rows.stream().mapToLong(Result::elapsedMs).average().orElse(0)),
                    Map.entry("medianMs", percentile(rows, 0.5)), Map.entry("p95Ms", percentile(rows, 0.95))));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("model", gateway.model);
        report.put("runtimeIsolation", "fresh Node process and QuickJS runtime per execution");
        report.put("scoring", "original ActionMatcher; primary normalizes equivalent timestamp representations to user zone; legacy score retained");
        report.put("modelParameters", Map.of("temperature", 0.1, "maxCompletionTokens", 3000,
                "experimentalReasoningEffort", generation.reasoningEffort(0) == null ? "provider-default" : generation.reasoningEffort(0),
                "experimentalRepairEffort", generation.reasoningEffort(1) == null ? "provider-default" : generation.reasoningEffort(1)));
        var digest = MessageDigest.getInstance("SHA-256");
        for (Class<?> type : List.of(AgentLoop.class, ExperimentalAgentLoop.class,
                ru.taskflow.assistant.application.planning.PlanningPromptBuilder.class,
                ru.taskflow.assistant.application.planning.PlanningSession.class,
                Class.forName("ru.taskflow.assistant.application.planning.ContextualChoicePolicy"),
                Class.forName("ru.taskflow.assistant.application.TitleChangeGuard"),
                Class.forName("ru.taskflow.task.application.TaskServiceImpl"), Class.forName("ru.taskflow.task.infrastructure.persistence.TaskRepository"),
                ru.taskflow.assistant.application.planning.PlanningGenerationSettings.class,
                ru.taskflow.assistant.application.planning.CodeModeExecutor.class,
                ru.taskflow.nlp.api.LlmToolRequest.class)) {
            try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                digest.update(bytes.readAllBytes());
            }
        }
        report.put("plannerBytecodeSha256", HexFormat.of().formatHex(digest.digest()));
        report.put("datasetSha256", hash);
        report.put("selectedRows", size);
        report.put("plannedMeasurements", size * repeats * modeCount);
        report.put("stoppedByBudget", stopped);
        report.put("httpAttempts", gateway.attempts.get());
        report.put("apiErrors", gateway.errors);
        report.put("summary", summary);
        report.put("measurements", results);
        report.put("traces", traces);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
        Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static long percentile(List<Result> rows, double fraction) {
        var sorted = rows.stream().mapToLong(Result::elapsedMs).sorted().toArray();
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * fraction) - 1)];
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
