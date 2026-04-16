package ru.taskflow.task.api.exception;

import ru.taskflow.shared.exception.NotFoundException;

import java.util.UUID;

public class GroupNotFoundException extends NotFoundException {

    public GroupNotFoundException(UUID groupId) {
        super("группа не найдена: " + groupId);
    }
}
