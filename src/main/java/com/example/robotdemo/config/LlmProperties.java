package com.example.robotdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmProperties(String provider, DeepSeek deepseek) {
    public record DeepSeek(String apiKey, String baseUrl, String model) {}
}
