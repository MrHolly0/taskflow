package ru.taskflow.nlp.infrastructure.config;

public class ToolCallProvidersUnavailableException extends RuntimeException {

    public ToolCallProvidersUnavailableException() {
        super("Поставщики модели недоступны");
    }
}
