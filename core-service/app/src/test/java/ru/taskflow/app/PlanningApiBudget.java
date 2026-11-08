package ru.taskflow.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class PlanningApiBudget {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    PlanningApiBudget(Path file) { this.file = file; }

    synchronized boolean reserve() {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (channel.size() > 1024) throw new IllegalStateException("invalid budget file");
            var buffer = ByteBuffer.allocate((int) channel.size());
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) break;
            var state = JSON.readTree(buffer.array());
            int limit = state.path("limit").asInt(-1), used = state.path("used").asInt(-1);
            if (limit < 1 || limit > 3000 || used < 0) throw new IllegalStateException("invalid budget");
            if (used >= limit) return false;
            var updated = JSON.createObjectNode().put("limit", limit).put("used", used + 1);
            byte[] bytes = JSON.writeValueAsBytes(updated);
            channel.position(0);
            var output = ByteBuffer.wrap(bytes);
            while (output.hasRemaining()) channel.write(output);
            channel.truncate(bytes.length);
            channel.force(true);
            return true;
        } catch (IOException e) { throw new IllegalStateException("budget unavailable", e); }
    }
}
