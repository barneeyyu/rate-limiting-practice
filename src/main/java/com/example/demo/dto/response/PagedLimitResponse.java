package com.example.demo.dto.response;

import java.util.List;

public record PagedLimitResponse(
    List<LimitResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {}
