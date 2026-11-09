package ru.taskflow.assistant.application.planning;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.assertThat;

class RelativeDayPolicyTest {
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "перенеси на через три дня в 10:05|3",
        "напомни послезавтра в 07:55. дату задачи не меняй|2",
        "добавь завтра в 10|1",
        "напомни через 25 дней|25",
        "перенеси через две недели|14",
        "вчера был срок|-1",
        "сегодня в 18|0"
    })
    void resolvesUnambiguousCalendarOffsets(String source,int days) {
        assertThat(RelativeDayPolicy.uniqueOffset(source)).hasValue(days);
    }
    @ParameterizedTest @ValueSource(strings={
        "завтра купить, послезавтра забрать",
        "не завтра, а послезавтра",
        "перенеси не на завтра, а на послезавтра",
        "завтра купить, через час забрать",
        "завтра купить, в пятницу забрать",
        "завтра купить, 25 числа забрать",
        "завтра купить, 25.11 забрать",
        "напомни через полтора дня",
        "напомни про врача",
        "через неделю сдать доклад, на следующей неделе его доделать",
        "завтра записаться на курс, на этой неделе подготовить документы"
    })
    void leavesMixedAndUnsupportedDateExpressionsUntouched(String source) {
        assertThat(RelativeDayPolicy.uniqueOffset(source)).isEmpty();
    }
}
