package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.RobotContract;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class DataLoader {
    private static final String DEFAULT_CONTRACT_RESOURCE = "data/contracts.json";
    private static final Path EDITABLE_CONTRACT_PATH = Path.of("data", "contracts.json");

    private final ObjectMapper mapper;

    public DataLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<RobotContract> contracts() {
        ensureEditableContracts();
        try (InputStream input = Files.newInputStream(EDITABLE_CONTRACT_PATH)) {
            return mapper.readValue(input, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("读取能力契约配置失败：" + EDITABLE_CONTRACT_PATH.toAbsolutePath(), e);
        }
    }

    public List<RobotContract> saveContracts(List<RobotContract> contracts) {
        try {
            Files.createDirectories(EDITABLE_CONTRACT_PATH.getParent());
            ObjectMapper writer = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
            writer.writeValue(EDITABLE_CONTRACT_PATH.toFile(), contracts);
            return contracts();
        } catch (Exception e) {
            throw new IllegalStateException("保存能力契约配置失败：" + EDITABLE_CONTRACT_PATH.toAbsolutePath(), e);
        }
    }

    public Map<String, Object> contractConfigInfo() {
        ensureEditableContracts();
        return Map.of(
                "path", EDITABLE_CONTRACT_PATH.toAbsolutePath().toString(),
                "robot_count", contracts().size()
        );
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

    private void ensureEditableContracts() {
        if (Files.exists(EDITABLE_CONTRACT_PATH)) return;
        try {
            Files.createDirectories(EDITABLE_CONTRACT_PATH.getParent());
            try (InputStream input = new ClassPathResource(DEFAULT_CONTRACT_RESOURCE).getInputStream()) {
                Files.copy(input, EDITABLE_CONTRACT_PATH);
            }
        } catch (IOException e) {
            throw new IllegalStateException("初始化可编辑能力契约配置失败：" + EDITABLE_CONTRACT_PATH.toAbsolutePath(), e);
        }
    }
}
