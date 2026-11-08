package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.DeclineReason;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.assistant.application.AgentOutcome;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PlanningLargeContextScorerTest {
    private final LocalDate day = LocalDate.of(2030, 11, 15);
    private final UUID target = UUID.randomUUID();
    private PlanningLargeContextCases.Scenario scenario(String id) {
        return PlanningLargeContextCases.all(day).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
    private AgentOutcome out(List<ProposedAction> actions) {
        return new AgentOutcome(actions, List.of(), null, List.of(), null, null, 1, false);
    }
    private ProposedAction action(AssistantActionType type, UUID id, Map<String,Object> fields) {
        return new ProposedAction(1, type, id, fields, "Проверка", true);
    }
    @Test void acceptsEquivalentInstantsButRejectsTheWrongMinuteAndReminderField() {
        var scenario = scenario("remind-relative-hidden");
        var expected = (OffsetDateTime) scenario.expected().getFirst().fields().get("reminder_at");
        var valid = out(List.of(action(AssistantActionType.REMIND, target,
                Map.of("reminder_at", expected.withOffsetSameInstant(ZoneOffset.UTC).toString()))));
        var score = PlanningLargeContextScorer.score(scenario, Map.of("relative",target), valid, false);
        assertThat(score.correct()).isTrue();assertThat(score.remindersCorrect()).isEqualTo(1);
        var wrong = out(List.of(action(AssistantActionType.REMIND, target, Map.of("reminder_at",expected.plusMinutes(1).toString()))));
        assertThat(PlanningLargeContextScorer.score(scenario, Map.of("relative",target), wrong, false).correct()).isFalse();
        wrong = out(List.of(action(AssistantActionType.REMIND, target, Map.of("deadline",expected.toString()))));
        assertThat(PlanningLargeContextScorer.score(scenario, Map.of("relative",target), wrong, false).remindersCorrect()).isZero();
    }
    @Test void rejectsOtherTargetsAndUnrequestedFieldsEvenWhenTheTimeMatches() {
        var scenario = scenario("reschedule-hidden");
        var at = scenario.expected().getFirst().fields().get("new_deadline");
        var other = out(List.of(action(AssistantActionType.RESCHEDULE, UUID.randomUUID(), Map.of("new_deadline",at))));
        assertThat(PlanningLargeContextScorer.score(scenario, Map.of("reschedule",target), other, false).matched()).isZero();
        var extra = out(List.of(action(AssistantActionType.RESCHEDULE, target, Map.of("new_deadline",at,"priority","HIGH"))));
        assertThat(PlanningLargeContextScorer.score(scenario, Map.of("reschedule",target), extra, false).errors())
                .contains("UNREQUESTED_FIELD_priority:reschedule");
    }
    @Test void oneActualActionCannotSatisfySeveralExpectedChanges() {
        var scenario = scenario("group-overdue");
        var action = action(AssistantActionType.RESCHEDULE, target, scenario.expected().getFirst().fields());
        var score = PlanningLargeContextScorer.score(scenario, Map.of("mass1",target,"mass2",target,"mass3",target), out(List.of(action)),false);
        assertThat(score.matched()).isEqualTo(1);assertThat(score.correct()).isFalse();
    }
    @Test void aTechnicalFailureDoesNotCountAsAClarifyingQuestion() {
        var technical = new AgentOutcome(List.of(), List.of("MODEL_UNAVAILABLE"), "Какую задачу?", List.of(),null,null,1,false);
        assertThat(PlanningLargeContextScorer.score(scenario("ambiguous"),Map.of(),technical,false).correct()).isFalse();
        var clarification = new AgentOutcome(List.of(),List.of("AMBIGUOUS"),"Для какого филиала?",List.of(),null,null,1,false);
        assertThat(PlanningLargeContextScorer.score(scenario("ambiguous"),Map.of(),clarification,false).correct()).isTrue();
        var control = new AgentOutcome(List.of(),List.of(),null,List.of(),"Какой филиал выбрать?",null,1,false,false,null,10,20,0,0,0,DeclineReason.QUESTION);
        assertThat(PlanningLargeContextScorer.score(scenario("ambiguous"),Map.of(),control,false).correct()).isTrue();
    }
    @Test void selectionOverflowRequiresAnExplicitSafeOutcome() {
        var overflow = new AgentOutcome(List.of(),List.of("SELECTION_LIMIT"),"Уточни период",List.of(),null,null,1,false);
        assertThat(PlanningLargeContextScorer.score(scenario("selection-limit"),Map.of(),overflow,false).correct()).isTrue();
        var partial = out(List.of(action(AssistantActionType.COMPLETE,target,Map.of())));
        assertThat(PlanningLargeContextScorer.score(scenario("selection-limit"),Map.of(),partial,false).correct()).isFalse();
        assertThat(PlanningLargeContextScorer.score(scenario("selection-limit"),Map.of(),out(List.of()),false).correct()).isFalse();
    }
}
