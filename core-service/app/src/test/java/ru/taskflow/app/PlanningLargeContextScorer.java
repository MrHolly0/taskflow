package ru.taskflow.app;

import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.DeclineReason;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.assistant.application.AgentOutcome;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

final class PlanningLargeContextScorer {
    record Score(boolean correct, int expected, int actual, int matched, int dateChecks, int datesCorrect,
                 int reminderChecks, int remindersCorrect, List<String> errors) {}
    private static final Set<String> TIMES = Set.of("deadline", "new_deadline", "reminder_at");
    private static final Set<String> FIELDS = Set.of("title", "description", "priority", "group", "tags", "deadline", "new_deadline", "reminder_at");
    private PlanningLargeContextScorer() {}

    static Score score(PlanningLargeContextCases.Scenario scenario, Map<String, UUID> ids, AgentOutcome out, boolean apiFailure) {
        List<String> errors = new ArrayList<>();
        if (apiFailure || out.llmFailed()) errors.add("API_FAILURE");
        if (scenario.control() != PlanningLargeContextCases.Control.ACTIONS) {
            if (!out.actions().isEmpty()) errors.add("UNEXPECTED_ACTIONS");
            String text = Objects.toString(out.clarification(), "") + " " + Objects.toString(out.assistantText(), "");
            boolean control = scenario.control() == PlanningLargeContextCases.Control.SELECTION_LIMIT
                    ? out.rejections().contains("SELECTION_LIMIT") || text.toLowerCase(Locale.ROOT).matches("(?s).*(больше 20|слишком много|уточн.*групп|уточн.*период).*" )
                    : out.clarification() != null && !out.clarification().isBlank()
                        || (out.declineReason() == DeclineReason.QUESTION || out.declineReason() == DeclineReason.UNCLEAR)
                            && text.toLowerCase(Locale.ROOT).matches("(?s).*(\\?|уточн|какой|какую|котор|выбер).*" );
            if (!control) errors.add("MISSING_CLARIFICATION");
            if (out.rejections().stream().anyMatch(code -> !Set.of("AMBIGUOUS", "SELECTION_LIMIT", "DEADLINE_REQUIRED").contains(code)))
                errors.add("TECHNICAL_FAILURE");
            return new Score(errors.isEmpty(), 0, out.actions().size(), 0, 0, 0, 0, 0, List.copyOf(errors));
        }
        if (!out.rejections().isEmpty()) errors.addAll(out.rejections());
        if (out.ambiguous() || out.clarification() != null || out.isDeclined()) errors.add("UNEXPECTED_CONTROL");
        boolean[] used = new boolean[out.actions().size()];
        int matched = 0, dateChecks = 0, dateCorrect = 0, reminderChecks = 0, reminderCorrect = 0;
        for (var expected : scenario.expected()) {
            int match = -1;
            for (int i = 0; i < used.length; i++) if (!used[i] && matches(expected, ids, out.actions().get(i))) { match = i; break; }
            ProposedAction actual = match < 0 ? null : out.actions().get(match);
            if (actual == null) errors.add("MISSING_" + expected.type() + ":" + expected.target());
            else { used[match] = true; matched++; if (!actual.accepted()) errors.add("UNACCEPTED_ACTION"); }
            for (var field : expected.fields().entrySet()) {
                Object got = actual == null ? null : actual.payload().get(field.getKey());
                boolean equal = TIMES.contains(field.getKey()) ? sameMinute(field.getValue(), got)
                        : field.getValue().toString().equalsIgnoreCase(String.valueOf(got));
                if (field.getKey().equals("reminder_at")) { reminderChecks++; if (equal) reminderCorrect++; }
                else if (TIMES.contains(field.getKey())) { dateChecks++; if (equal) dateCorrect++; }
                if (!equal) errors.add("FIELD_" + field.getKey() + ":" + expected.target() + ":" + got);
            }
            if (actual != null) for (String field : FIELDS) {
                Object value = actual.payload().get(field);
                if (value == null || value instanceof String s && s.isBlank() || value instanceof Collection<?> c && c.isEmpty()) continue;
                if (expected.fields().containsKey(field)) continue;
                if (expected.type() == AssistantActionType.CREATE && (field.equals("title")
                        || field.equals("priority") && "MEDIUM".equals(value.toString()))) continue;
                errors.add("UNREQUESTED_FIELD_" + field + ":" + expected.target());
            }
        }
        for (int i = 0; i < used.length; i++) if (!used[i]) errors.add("EXTRA_" + out.actions().get(i).type() + ":" + out.actions().get(i).targetTaskId());
        return new Score(errors.isEmpty(), scenario.expected().size(), out.actions().size(), matched,
                dateChecks, dateCorrect, reminderChecks, reminderCorrect, List.copyOf(errors));
    }
    private static boolean matches(PlanningLargeContextCases.Expected expected, Map<String, UUID> ids, ProposedAction actual) {
        if (expected.type() != actual.type()) return false;
        if (expected.target() != null) return ids.get(expected.target()) != null
                && Objects.equals(ids.get(expected.target()), actual.targetTaskId());
        if (actual.targetTaskId() != null) return false;
        return expected.titlePart() != null && String.valueOf(actual.payload().get("title")).toLowerCase(Locale.ROOT)
                .contains(expected.titlePart().toLowerCase(Locale.ROOT));
    }
    private static boolean sameMinute(Object expected, Object actual) {
        if (actual == null) return false;
        try {
            return OffsetDateTime.parse(expected.toString()).toInstant().truncatedTo(ChronoUnit.MINUTES)
                    .equals(OffsetDateTime.parse(actual.toString()).toInstant().truncatedTo(ChronoUnit.MINUTES));
        } catch (RuntimeException ignored) { return false; }
    }
}
