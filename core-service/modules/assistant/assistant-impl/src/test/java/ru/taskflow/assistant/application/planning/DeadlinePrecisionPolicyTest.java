package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;

class DeadlinePrecisionPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"Через неделю сдать доклад", "До 17 ноября сдать доклад", "На 17 ноября добавить задачу", "На 17.11 сдать доклад", "До завтра доделать часть курсовой", "Завтра купить секундомер"})
    void calendarDayWithoutClockMeansEndOfUsersDay(String source) throws Exception {
        var actions = json.createArrayNode().add(json.readTree("{\"type\":\"create\",\"title\":\"Доклад\",\"deadline\":\"2026-11-17T09:00:00+03:00\"}"));
        DeadlinePrecisionPolicy.normalize(actions, source, ZoneId.of("Europe/Moscow"));
        assertThat(actions.get(0).path("deadline").asText()).isEqualTo("2026-11-17T23:59+03:00");
        assertThat(actions.get(0).path("deadline_time_inferred").asBoolean()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"Завтра в 09:00 сдать доклад", "Завтра утром сдать доклад", "Завтра в 9 сдать доклад", "Через час сдать доклад", "Завтра в пятнадцать сдать доклад", "Завтра к полудню сдать доклад", "Завтра в 18ч сдать доклад", "Завтра к девяти сдать доклад", "Завтра в 9.30 сдать доклад", "Завтра к двум сдать доклад"})
    void explicitClockAndDurationsAreNotReinterpreted(String source) throws Exception {
        var actions = json.createArrayNode().add(json.readTree("{\"type\":\"create\",\"title\":\"Доклад\",\"deadline\":\"2026-11-17T09:00:00+03:00\"}"));
        DeadlinePrecisionPolicy.normalize(actions, source, ZoneId.of("Europe/Moscow"));
        assertThat(actions.get(0).path("deadline").asText()).isEqualTo("2026-11-17T09:00:00+03:00");
        assertThat(actions.get(0).has("deadline_time_inferred")).isFalse();
    }
}
