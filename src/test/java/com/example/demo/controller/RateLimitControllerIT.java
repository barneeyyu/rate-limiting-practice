package com.example.demo.controller;

import com.example.demo.dto.request.CreateLimitRequest;
import com.example.demo.dto.response.CheckResponse;
import com.example.demo.dto.response.LimitResponse;
import com.example.demo.dto.response.PagedLimitResponse;
import com.example.demo.dto.response.UsageResponse;
import com.example.demo.event.EventPublisher;
import com.example.demo.event.RateLimitEventConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class RateLimitControllerIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @MockBean
    private EventPublisher eventPublisher;

    @MockBean
    private RateLimitEventConsumer eventConsumer;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void createLimit_validRequest_returns201() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("test-api-key", 100, 60);

        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.apiKey").value("test-api-key"))
            .andExpect(jsonPath("$.limit").value(100))
            .andExpect(jsonPath("$.windowSeconds").value(60));
    }

    @Test
    void createLimit_invalidLimit_returns400() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("test-api-key", 0, 60);

        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Validation failed"))
            .andExpect(jsonPath("$.details", hasItem("limit must be at least 1")));
    }

    @Test
    void check_noApiKey_returns401() throws Exception {
        mockMvc.perform(get("/check"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("API key is required"));
    }

    @Test
    void check_noRuleConfigured_returns200Allowed() throws Exception {
        mockMvc.perform(get("/check").param("apiKey", "unknown-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.message").value("No rate limit configured"));
    }

    @Test
    void check_underLimit_returns200WithHeaders() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("check-test-key", 100, 60);
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/check").param("apiKey", "check-test-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.currentCount").value(1))
            .andExpect(jsonPath("$.remaining").value(99))
            .andExpect(header().exists("X-RateLimit-Limit"))
            .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    @Test
    void check_overLimit_returns429() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("limit-test-key", 2, 60);
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/check").param("apiKey", "limit-test-key"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/check").param("apiKey", "limit-test-key"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/check").param("apiKey", "limit-test-key"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.allowed").value(false))
            .andExpect(header().exists("Retry-After"));
    }

    @Test
    void usage_existingKey_returnsUsage() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("usage-test-key", 100, 60);
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/check").param("apiKey", "usage-test-key"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/usage").param("apiKey", "usage-test-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apiKey").value("usage-test-key"))
            .andExpect(jsonPath("$.currentCount").value(1))
            .andExpect(jsonPath("$.limit").value(100));
    }

    @Test
    void deleteLimit_existing_returns204() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("delete-test-key", 100, 60);
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(delete("/limits/delete-test-key"))
            .andExpect(status().isNoContent());
    }

    @Test
    void deleteLimit_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/limits/non-existent-key"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", containsString("not found")));
    }

    @Test
    void listLimits_withData_returnsPaginatedResponse() throws Exception {
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateLimitRequest("list-key-1", 100, 60))))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateLimitRequest("list-key-2", 50, 30))))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/limits").param("page", "0").param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(2))))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(10));
    }
}
