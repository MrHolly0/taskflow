package ru.taskflow.assistant.application.planning;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class WeekdayPolicyTest {
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "2026-11-12|на следующей неделе в понедельник встреча|2026-11-16",
        "2026-11-12|на следующей неделе во вторник встреча|2026-11-17",
        "2026-11-12|в среду на будущей неделе встреча|2026-11-18",
        "2026-11-12|на следующей неделе в четверг встреча|2026-11-19",
        "2026-11-12|на следующей неделе в пятницу встреча|2026-11-20",
        "2026-11-12|на следующей неделе в субботу встреча|2026-11-21",
        "2026-11-12|на следующей неделе в воскресенье встреча|2026-11-22",
        "2026-11-16|на следующей неделе во вторник встреча|2026-11-24",
        "2026-11-15|на следующей неделе во вторник встреча|2026-11-17",
        "2026-11-28|на следующей неделе во вторник встреча|2026-12-01",
        "2026-12-31|на следующей неделе во вторник встреча|2027-01-05"
    }) void resolvesCalendarWeek(String today,String source,String expected) {
        assertThat(WeekdayPolicy.nextWeek(source,LocalDate.parse(today))).contains(LocalDate.parse(expected));
    }
    @ParameterizedTest @ValueSource(strings={
        "на следующей неделе во вторник и в среду две встречи",
        "не на следующей неделе во вторник, а на этой",
        "на следующей неделе не во вторник, а в среду",
        "на следующей неделе во вторник и завтра две встречи",
        "на следующей неделе во вторник 20 ноября встреча",
        "на следующей неделе во вторник, напомни через два дня",
        "через неделю во вторник встреча",
        "встреча на следующей неделе",
        "во вторник встреча"
    }) void leavesMultipleAndUnspecifiedAnchorsToThePlanner(String source) {
        assertThat(WeekdayPolicy.nextWeek(source,LocalDate.of(2026,11,12))).isEmpty();
    }
}
