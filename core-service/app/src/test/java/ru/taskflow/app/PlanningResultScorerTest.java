package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import ru.taskflow.app.experiment.ActionMatcher;
import ru.taskflow.app.experiment.ExpectedAction;
import ru.taskflow.app.experiment.ExpectedOutcome;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PlanningResultScorerTest {
    @Test void scoresEquivalentInstantsEquallyWithoutChangingTheOriginalPayload() {
        var utc = new ProposedAction(1, AssistantActionType.CREATE, null,
                Map.of("title", "Купить блокнот", "deadline", "2030-11-15T15:00:00Z"), "Купить блокнот", true);
        var local = new ProposedAction(1, AssistantActionType.CREATE, null,
                Map.of("title", "Купить блокнот", "deadline", "2030-11-15T18:00:00+03:00"), "Купить блокнот", true);
        var expected = new ExpectedOutcome(1, false,
                List.of(new ExpectedAction("create", "блокнот", null, 1, 18, null, null)));
        var zone = ZoneId.of("Europe/Moscow");
        var matcher = new ActionMatcher();
        for (var action : List.of(utc, local))
            assertThat(matcher.match(PlanningResultScorer.normalizeTimes(List.of(action), zone), expected,
                    Map.of(), LocalDate.of(2030, 11, 14), zone).fullyCorrect()).isTrue();
        assertThat(utc.payload()).containsEntry("deadline", "2030-11-15T15:00:00Z");
        assertThat(matcher.match(List.of(utc), expected, Map.of(), LocalDate.of(2030, 11, 14), zone).fullyCorrect()).isFalse();
    }
}
