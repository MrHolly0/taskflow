package ru.taskflow.nlp.api;

import java.time.Instant;
import java.util.List;

public record NlpParsedTask(
    String title,
    String description,
    String priority,
    Instant deadline,
    String group,
    List<String> tags,
    String recurrence
) {
}
