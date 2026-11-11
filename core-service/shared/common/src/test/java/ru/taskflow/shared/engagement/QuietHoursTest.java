package ru.taskflow.shared.engagement;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class QuietHoursTest {
    @Test void overnightDaytimeDisabledAndBoundariesUseLocalTime() {
        var zone=ZoneId.of("Europe/Moscow");var now=Instant.parse("2030-11-11T20:00:00Z");
        assertThat(QuietHours.deferUntil(now,zone,true,"23:00","07:00")).isEqualTo(OffsetDateTime.parse("2030-11-12T07:00:00+03:00"));
        assertThat(QuietHours.deferUntil(now,zone,false,"23:00","07:00")).isNull();
        assertThat(QuietHours.deferUntil(Instant.parse("2030-11-12T04:00:00Z"),zone,true,"23:00","07:00")).isNull();
        assertThat(QuietHours.deferUntil(Instant.parse("2030-11-12T07:00:00Z"),zone,true,"09:00","18:00")).isEqualTo(OffsetDateTime.parse("2030-11-12T18:00:00+03:00"));
    }
    @Test void daylightSavingTransitionEndsAtTheActualLocalBoundary() {
        var zone=ZoneId.of("Europe/Berlin");
        assertThat(QuietHours.deferUntil(Instant.parse("2026-10-25T00:15:00Z"),zone,true,"23:00","02:30")).isEqualTo(OffsetDateTime.parse("2026-10-25T02:30:00+02:00"));
        assertThat(QuietHours.deferUntil(Instant.parse("2026-10-25T01:15:00Z"),zone,true,"23:00","02:30")).isEqualTo(OffsetDateTime.parse("2026-10-25T02:30:00+01:00"));
        assertThat(QuietHours.deferUntil(Instant.parse("2026-03-29T00:15:00Z"),zone,true,"23:00","02:30")).isEqualTo(OffsetDateTime.parse("2026-03-29T03:00:00+02:00"));
    }
}
