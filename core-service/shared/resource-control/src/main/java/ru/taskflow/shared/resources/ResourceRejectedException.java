package ru.taskflow.shared.resources;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ResourceRejectedException extends ResponseStatusException {
    private final long retryAfterSeconds;
    public ResourceRejectedException(HttpStatus status, String reason) { this(status,reason,5); }
    public ResourceRejectedException(HttpStatus status,String reason,long retryAfterSeconds) {
        super(status,reason);this.retryAfterSeconds=Math.max(1,retryAfterSeconds);
    }
    @Override public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if(getStatusCode().value()==429 || getStatusCode().value()==503)
            headers.set("Retry-After", ""+retryAfterSeconds);
        headers.set("X-Resource-Rejected", "true");
        return headers;
    }
    public static ResourceRejectedException unavailable() {
        return new ResourceRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "Защита ресурсов временно недоступна");
    }
}
