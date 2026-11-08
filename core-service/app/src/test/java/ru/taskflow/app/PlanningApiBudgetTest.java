package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class PlanningApiBudgetTest {
    @TempDir Path directory;
    @Test void sharesTheLimitAcrossRunnerInstances() throws Exception {
        Path file = directory.resolve("budget.json");
        Files.writeString(file, "{\"limit\":2,\"used\":0}");
        assertThat(new PlanningApiBudget(file).reserve()).isTrue();
        assertThat(new PlanningApiBudget(file).reserve()).isTrue();
        assertThat(new PlanningApiBudget(file).reserve()).isFalse();
    }
    @Test void failsClosedOnDamagedOrMissingBudget() throws Exception {
        Path file = directory.resolve("budget.json");
        assertThatThrownBy(() -> new PlanningApiBudget(file).reserve()).isInstanceOf(IllegalStateException.class);
        Files.writeString(file, "{\"limit\":5000,\"used\":0}");
        assertThatThrownBy(() -> new PlanningApiBudget(file).reserve()).isInstanceOf(IllegalStateException.class);
        Files.writeString(file, "broken");
        assertThatThrownBy(() -> new PlanningApiBudget(file).reserve()).isInstanceOf(IllegalStateException.class);
    }
}
