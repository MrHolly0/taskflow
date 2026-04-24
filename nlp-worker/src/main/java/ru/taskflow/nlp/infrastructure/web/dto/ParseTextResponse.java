package ru.taskflow.nlp.infrastructure.web.dto;

import ru.taskflow.nlp.domain.ParsedTask;

import java.util.List;

public record ParseTextResponse(List<ParsedTask> tasks) {
}
