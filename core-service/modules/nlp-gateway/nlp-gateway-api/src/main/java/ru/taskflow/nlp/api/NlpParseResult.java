package ru.taskflow.nlp.api;

import java.util.List;

public record NlpParseResult(List<NlpParsedTask> tasks) {
}
