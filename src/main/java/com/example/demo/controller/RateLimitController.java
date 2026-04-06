package com.example.demo.controller;

import com.example.demo.dto.request.CreateLimitRequest;
import com.example.demo.dto.response.*;
import com.example.demo.entity.RateLimitConfig;
import com.example.demo.service.ConfigService;
import com.example.demo.service.RateLimitService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
public class RateLimitController {

    private final RateLimitService rateLimitService;
    private final ConfigService configService;

    public RateLimitController(RateLimitService rateLimitService, ConfigService configService) {
        this.rateLimitService = rateLimitService;
        this.configService = configService;
    }

    @PostMapping("/limits")
    public ResponseEntity<LimitResponse> createLimit(@Valid @RequestBody CreateLimitRequest request) {
        RateLimitConfig config = configService.createOrUpdate(
            request.apiKey(), request.limit(), request.windowSeconds());

        LimitResponse response = new LimitResponse(
            config.getApiKey(), config.getRequestLimit(), config.getWindowSeconds());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/check")
    public ResponseEntity<CheckResponse> check(@RequestParam(required = false) String apiKey) {
        CheckResponse response = rateLimitService.check(apiKey);

        HttpHeaders headers = new HttpHeaders();
        if (response.limit() != null) {
            headers.set("X-RateLimit-Limit", String.valueOf(response.limit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(response.remaining()));
            headers.set("X-RateLimit-Reset", String.valueOf(Instant.now().getEpochSecond() + 60));
        }

        return ResponseEntity.ok().headers(headers).body(response);
    }

    @GetMapping("/usage")
    public ResponseEntity<UsageResponse> usage(@RequestParam String apiKey) {
        UsageResponse response = rateLimitService.getUsage(apiKey);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/limits/{apiKey}")
    public ResponseEntity<Void> deleteLimit(@PathVariable String apiKey) {
        configService.delete(apiKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/limits")
    public ResponseEntity<PagedLimitResponse> listLimits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Page<RateLimitConfig> pageResult = configService.findAll(PageRequest.of(page, size));

        List<LimitResponse> content = pageResult.getContent().stream()
            .map(c -> new LimitResponse(c.getApiKey(), c.getRequestLimit(), c.getWindowSeconds()))
            .toList();

        PagedLimitResponse response = new PagedLimitResponse(
            content,
            pageResult.getNumber(),
            pageResult.getSize(),
            pageResult.getTotalElements(),
            pageResult.getTotalPages()
        );

        return ResponseEntity.ok(response);
    }
}
