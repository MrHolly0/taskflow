package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class CodeModeExecutor {
    private final ObjectMapper json;
    private final String node;
    private final String runner;
    private final Semaphore permits = new Semaphore(1);
    private final ExecutorService workers = Executors.newFixedThreadPool(1, Thread.ofPlatform().daemon().name("code-plan-", 0).factory());

    public CodeModeExecutor(ObjectMapper json, @Value("${app.assistant.code-mode.node:node}") String node,
            @Value("${app.assistant.code-mode.runner:code-runtime/runner.mjs}") String runner) {
        this.json = json; this.node = node;
        File configured = new File(runner);
        if (!configured.isAbsolute() && !configured.isFile()) {
            File base = new File(System.getProperty("user.dir"));
            for (int depth = 0; depth < 4 && base != null; depth++, base = base.getParentFile()) {
                File candidate = new File(base, runner);
                if (candidate.isFile()) { configured = candidate; break; }
            }
        }
        this.runner = configured.getAbsolutePath();
    }

    public JsonNode execute(String code, OffsetDateTime now, PlanningSession session) {
        if (code == null || code.getBytes(StandardCharsets.UTF_8).length > 16384) throw new PlanningException("CODE_LIMIT");
        if (!permits.tryAcquire()) throw new PlanningException("EXECUTOR_BUSY");
        var process = new AtomicReference<Process>();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        Future<JsonNode> work = workers.submit(() -> {
            try {
                var builder = new ProcessBuilder(node, "--max-old-space-size=64", new File(runner).getAbsolutePath());
                builder.environment().clear();
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                if (cancelled.get()) throw new PlanningException("EXECUTION_INTERRUPTED");
                Process child = builder.start(); process.set(child);
                if (cancelled.get()) throw new PlanningException("EXECUTION_INTERRUPTED");
                try (var input = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                     var output = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8))) {
                    send(output, json.writeValueAsString(Map.of("code", code, "now", now.toString())));
                    for (int frames = 0; frames < 10; frames++) {
                        JsonNode frame = json.readTree(readFrame(input));
                        if ("result".equals(frame.path("type").asText())) return frame.get("value");
                        if ("error".equals(frame.path("type").asText())) throw new PlanningException("PROGRAM_ERROR");
                        if (!"call".equals(frame.path("type").asText()) || !"findTasks".equals(frame.path("method").asText()))
                            throw new PlanningException("INVALID_FRAME");
                        send(output, json.writeValueAsString(Map.of("result", session.select(frame.get("args")))));
                    }
                    throw new PlanningException("TOOL_LIMIT");
                }
            } catch (PlanningException e) { throw e; }
            catch (Exception e) { throw new PlanningException("EXECUTOR_UNAVAILABLE"); }
            finally { Process child = process.get(); if (child != null) child.destroyForcibly(); permits.release(); }
        });
        try { return work.get(3, TimeUnit.SECONDS); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof PlanningException failure) throw failure;
            throw new PlanningException("EXECUTOR_UNAVAILABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new PlanningException("EXECUTION_INTERRUPTED");
        } catch (TimeoutException e) { throw new PlanningException("EXECUTION_TIMEOUT"); }
        finally { cancelled.set(true); Process child = process.get(); if (child != null && child.isAlive()) child.destroyForcibly(); work.cancel(true); }
    }

    private String readFrame(BufferedReader input) throws IOException {
        StringBuilder frame = new StringBuilder();
        for (int c; (c = input.read()) != -1;) {
            if (c == '\n') return frame.toString();
            if (frame.length() >= 65536) throw new IOException("FRAME_LIMIT");
            frame.append((char)c);
        }
        throw new IOException("RUNNER_CLOSED");
    }
    private void send(BufferedWriter output, String frame) throws IOException { output.write(frame); output.newLine(); output.flush(); }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
