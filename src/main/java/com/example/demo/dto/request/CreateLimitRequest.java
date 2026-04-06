package com.example.demo.dto.request;

import jakarta.validation.constraints.*;

public record CreateLimitRequest(
    @NotBlank(message = "apiKey is required")
    String apiKey,

    @NotNull(message = "limit is required")
    @Min(value = 1, message = "limit must be at least 1")
    @Max(value = 1000000, message = "limit must not exceed 1000000")
    Integer limit,

    @NotNull(message = "windowSeconds is required")
    @Min(value = 1, message = "windowSeconds must be at least 1")
    @Max(value = 86400, message = "windowSeconds must not exceed 86400")
    Integer windowSeconds
) {}
