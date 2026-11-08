package ru.taskflow.app;

import ru.taskflow.assistant.api.dto.ProposedAction;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;

final class PlanningResultScorer {
    private PlanningResultScorer() {}

    static List<ProposedAction> normalizeTimes(List<ProposedAction> actions, ZoneId zone) {
        return actions.stream().map(action -> {
            var payload = new HashMap<>(action.payload());
            for (String field : List.of("deadline", "new_deadline", "reminder_at")) {
                Object value = payload.get(field);
                if (value == null) continue;
                try {
                    payload.put(field, OffsetDateTime.parse(value.toString()).atZoneSameInstant(zone).toOffsetDateTime().toString());
                } catch (java.time.format.DateTimeParseException ignored) {
                    // Невалидное значение остаётся невалидным для общего оценщика.
                }
            }
            return new ProposedAction(action.ordinal(), action.type(), action.targetTaskId(), payload,
                    action.summary(), action.accepted());
        }).toList();
    }
}
