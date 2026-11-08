package ru.taskflow.assistant.application.planning;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalTime;
import static org.assertj.core.api.Assertions.assertThat;

class FloatingReminderTimePolicyTest {
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "напомни в 18:00 забрать посылку|18:00",
        "поставь напоминание на 08:25 про письмо|08:25",
        "напомни в 6 вечера полить цветы|18:00",
        "напомни в 12 ночи закрыть окно|00:00"
    })
    void extractsOnlyAnUnqualifiedSingleClock(String source,String time) {
        assertThat(FloatingReminderTimePolicy.from(source)).isEqualTo(LocalTime.parse(time));
    }
    @ParameterizedTest @ValueSource(strings={
        "напомни 25 числа в 18:00 забрать посылку",
        "напомни двадцать пятого в 18:00 забрать посылку",
        "напомни на следующей неделе к среде в 18:00",
        "напомни 25.11 в 18:00 забрать посылку",
        "напомни первого января в 18:00",
        "напомни за час до срока в 18:00",
        "напомни через час проверить почту",
        "напомни в 18:00 и в 19:00",
        "поставь напоминание на 10 минут",
        "напомни в 12 утра",
        "напомни в 18 утра",
        "напомни в 18:99",
        "напомни в 18:40:20"
    })
    void leavesDatesDurationsAndAmbiguousClocksToTheFullPlan(String source) {
        assertThat(FloatingReminderTimePolicy.from(source)).isNull();
    }
}
