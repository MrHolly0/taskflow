package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import java.time.ZoneId;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SummaryTimezoneTest {
    final SummaryRenderer renderer=new SummaryRenderer();
    @Test void equivalentOffsetsProduceTheSameUserFacingTimeWithoutChangingThePayload() {
        var utc=Map.<String,Object>of("reminder_at","2026-11-16T11:00Z");
        var local=Map.<String,Object>of("reminder_at","2026-11-16T14:00+03:00");
        var zone=ZoneId.of("Europe/Moscow");
        assertThat(renderer.localize(AssistantActionType.REMIND,"Напомнить — Встреча → 16.11 11:00",utc,zone))
                .isEqualTo(renderer.localize(AssistantActionType.REMIND,"Напомнить — Встреча → 16.11 14:00",local,zone))
                .isEqualTo("Напомнить — Встреча → 16.11 14:00");
        assertThat(utc.get("reminder_at")).isEqualTo("2026-11-16T11:00Z");
    }
    @Test void aDifferentTimezoneMayMoveTheCalendarDay() {
        var payload=Map.<String,Object>of("title","Встреча","deadline","2026-11-16T23:30Z");
        assertThat(renderer.localize(AssistantActionType.CREATE,"Создать — Встреча · до 16.11 23:30",payload,ZoneId.of("Asia/Tokyo")))
                .isEqualTo("Создать — Встреча · до 17.11 08:30");
    }
    @Test void userChangingTimezonesDoesNotKeepThePreviouslyRenderedClock() {
        var payload=Map.<String,Object>of("new_deadline","2026-11-16T11:00Z");
        assertThat(renderer.localize(AssistantActionType.RESCHEDULE,"Перенести — Встреча → 16.11 14:00",payload,ZoneId.of("Europe/Berlin")))
                .isEqualTo("Перенести — Встреча → 16.11 12:00");
    }
}
