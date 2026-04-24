package ru.taskflow.nlp.domain;

import java.time.Instant;
import java.util.List;

public record ParsedTask(
    String title,
    String description,
    String priority,
    Instant deadline,
    String group,
    List<String> tags,
    String recurrence
) {
}
