package com.example.demo.exception;

import com.example.demo.dto.response.CheckResponse;
import com.example.demo.dto.response.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<CheckResponse> handleRateLimitExceeded(RateLimitExceededException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(ex.getLimit()));
        headers.set("X-RateLimit-Remaining", "0");
        headers.set("X-RateLimit-Reset", String.valueOf(Instant.now().getEpochSecond() + ex.getRetryAfterSeconds()));
        headers.set("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));

        CheckResponse response = CheckResponse.blocked(ex.getCurrentCount(), ex.getLimit(), ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(response);
    }

    @ExceptionHandler(ApiKeyRequiredException.class)
    public ResponseEntity<ErrorResponse> handleApiKeyRequired(ApiKeyRequiredException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler(ConfigNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleConfigNotFound(ConfigNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getDefaultMessage())
            .toList();
        return ResponseEntity.badRequest().body(ErrorResponse.withDetails("Validation failed", details));
    }
}
