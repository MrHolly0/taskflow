package ru.taskflow.shared.resources;

import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Order(-100)
public class ResourceExceptionHandler {
    @ExceptionHandler(ResourceRejectedException.class)
    public ResponseEntity<ProblemDetail> reject(ResourceRejectedException exception) {
        return ResponseEntity.status(exception.getStatusCode()).headers(exception.getHeaders())
                .body(ProblemDetail.forStatusAndDetail(exception.getStatusCode(),exception.getReason()));
    }
}
