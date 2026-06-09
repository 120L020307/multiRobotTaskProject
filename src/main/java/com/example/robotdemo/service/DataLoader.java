package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.RobotContract;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Service
public class DataLoader {
    private final ObjectMapper mapper;

    public DataLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<RobotContract> contracts() {
        return read("data/contracts.json", new TypeReference<>() {});
    }

    public List<Map<String, Object>> samples() {
        return read("data/sample_tasks.json", new TypeReference<>() {});
    }

    private <T> T read(String path, TypeReference<T> type) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(input, type);
        } catch (Exception e) {
            throw new IllegalStateException("读取资源失败：" + path, e);
        }
    }
}
