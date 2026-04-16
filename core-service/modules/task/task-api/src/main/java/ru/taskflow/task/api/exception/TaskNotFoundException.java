package ru.taskflow.task.api.exception;

import ru.taskflow.shared.exception.NotFoundException;

import java.util.UUID;

public class TaskNotFoundException extends NotFoundException {

    public TaskNotFoundException(UUID taskId) {
        super("задача не найдена: " + taskId);
    }
}
