package com.example.demo.repository;

import com.example.demo.entity.RateLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, Long> {
    Optional<RateLimitConfig> findByApiKey(String apiKey);
    boolean existsByApiKey(String apiKey);
    void deleteByApiKey(String apiKey);
}
