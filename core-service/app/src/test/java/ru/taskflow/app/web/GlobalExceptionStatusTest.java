package ru.taskflow.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionStatusTest {
    @Test
    void keepsConflictAndOverloadStatuses() {
        var handler = new GlobalExceptionHandler();
        var conflict = handler.handleStatus(new ResponseStatusException(HttpStatus.CONFLICT, "Другие данные"));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        var busy = handler.handleStatus(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Занято"));
        assertThat(busy.getStatusCode().value()).isEqualTo(503);
        assertThat(busy.getHeaders().getFirst("Retry-After")).isEqualTo("5");
    }
}
