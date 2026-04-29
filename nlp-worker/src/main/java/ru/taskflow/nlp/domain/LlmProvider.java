package ru.taskflow.nlp.domain;

import java.util.List;

public interface LlmProvider {
    ParsedTasks parseTasksFromText(String text, String userTimezone, String userLanguage, List<String> existingGroups);
}
