package ru.taskflow.nlp.domain;

public interface LlmProvider {
    ParsedTasks parseTasksFromText(String text, String userTimezone, String userLanguage);
}
