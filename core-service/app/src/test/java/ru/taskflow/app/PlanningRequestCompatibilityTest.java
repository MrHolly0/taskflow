package ru.taskflow.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.nlp.api.LlmToolRequest;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PlanningRequestCompatibilityTest {
    @Test void defaultRequestKeepsTheOldWireShape() {
        var mapper = new ObjectMapper();
        var request = mapper.valueToTree(new LlmToolRequest(List.of(), List.of()));
        assertThat(request.size()).isEqualTo(2);
        assertThat(request.has("reasoningEffort")).isFalse();
        assertThat(mapper.valueToTree(new LlmToolRequest(List.of(), List.of(), "low")).path("reasoningEffort").asText()).isEqualTo("low");
    }

    @Test void rejectsUnsupportedEffortBeforeSendingTheRequest() {
        assertThatThrownBy(() -> new LlmToolRequest(List.of(), List.of(), "unlimited"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
