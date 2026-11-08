package ru.taskflow.assistant.application.planning;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class ContextualChoicePolicyTest {
    @ParameterizedTest
    @CsvSource({"отчёт,отчёт","почта,почт","закрыть кино,кино","позвони врачу,врач","убрать задачу про мусор,мусор","сделать презентацию,презентаци"})
    void identifiesOnlyOneUnspecifiedTopic(String text,String topic) { assertThat(ContextualChoicePolicy.topic(text)).isEqualTo(topic); }
    @ParameterizedTest
    @ValueSource(strings={"я купил корм","полил цветы","отмени задачу про мусор","перенести спортзал","проект по клиенту","завтра отчёт","встреча в 14:00","купи молоко и хлеб"})
    void factsAndExplicitInstructionsAreNotTurnedIntoChoices(String text) { assertThat(ContextualChoicePolicy.topic(text)).isNull(); }
}
