package com.example.robotdemo.controller;

import com.example.robotdemo.config.LlmProperties;
import com.example.robotdemo.model.Models.ManualGraphRequest;
import com.example.robotdemo.model.Models.PipelineRequest;
import com.example.robotdemo.model.Models.PipelineResponse;
import com.example.robotdemo.model.Models.RobotContract;
import com.example.robotdemo.service.AgentOrchestrator;
import com.example.robotdemo.service.DataLoader;
import com.example.robotdemo.service.RunLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final AgentOrchestrator orchestrator;
    private final DataLoader dataLoader;
    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ApiController(AgentOrchestrator orchestrator, DataLoader dataLoader, LlmProperties properties, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.dataLoader = dataLoader;
        this.properties = properties;
        this.mapper = mapper;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String key = properties.deepseek() == null ? null : properties.deepseek().apiKey();
        String model = properties.deepseek() == null ? "deepseek" : properties.deepseek().model();
        return Map.of(
                "ok", true,
                "name", "robot-taskgraph-real-llm-agents-java",
                "mode", "real_llm_agents_java",
                "provider", "deepseek",
                "model", model == null ? "deepseek-v4-flash" : model,
                "has_llm_key", key != null && !key.isBlank()
        );
    }

    @GetMapping("/samples")
    public Object samples() {
        return dataLoader.samples();
    }

    @GetMapping("/contracts")
    public Object contracts() {
        return dataLoader.contracts();
    }

    @GetMapping("/contracts/config")
    public Object contractConfig() {
        return dataLoader.contractConfigInfo();
    }

    @PutMapping("/contracts")
    public ResponseEntity<?> saveContracts(@RequestBody List<RobotContract> contracts) {
        try {
            if (contracts == null || contracts.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "能力契约不能为空", "code", "empty_contracts"));
            }
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "contracts", dataLoader.saveContracts(contracts),
                    "config", dataLoader.contractConfigInfo()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage(), "code", "contract_save_error"));
        }
    }

    @PostMapping("/pipeline")
    public ResponseEntity<?> pipeline(@RequestBody PipelineRequest request) {
        try {
            String text = request.text();
            if (text == null || text.isBlank()) {
                text = String.valueOf(dataLoader.samples().get(0).get("text"));
            }
            PipelineResponse response = orchestrator.run(text, request.event());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage(), "code", "java_backend_error"));
        }
    }

    @PostMapping("/graphs/manual/run")
    public ResponseEntity<?> runManualGraph(@RequestBody ManualGraphRequest request) {
        try {
            if (request.graph() == null || request.graph().nodes() == null || request.graph().nodes().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "手动任务图不能为空，至少需要 1 个节点", "code", "empty_manual_graph"));
            }
            String text = request.text();
            if (text == null || text.isBlank()) {
                text = request.graph().raw_text() == null ? "手动可视化建模任务" : request.graph().raw_text();
            }
            PipelineResponse response = orchestrator.runManualGraph(text, request.graph(), request.event());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage(), "code", "manual_graph_error"));
        }
    }

    @PostMapping("/pipeline/stream")
    public SseEmitter pipelineStream(@RequestBody PipelineRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        String runId = UUID.randomUUID().toString();
        executor.execute(() -> {
            try {
                String text = request.text();
                if (text == null || text.isBlank()) {
                    text = String.valueOf(dataLoader.samples().get(0).get("text"));
                }
                send(emitter, "log", Map.of("run_id", runId, "stage", "Pipeline", "direction", "info", "title", "开始运行闭环流程"));
                RunLogger runLogger = new RunLogger(runId, log, mapper, entry -> send(emitter, "log", entry));
                PipelineResponse response = orchestrator.run(text, request.event(), runLogger);
                send(emitter, "result", response);
                emitter.complete();
            } catch (Exception e) {
                log.error("[{}] pipeline stream failed", runId, e);
                Map<String, Object> errorPayload = new LinkedHashMap<>();
                errorPayload.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                Map<String, Object> logPayload = new LinkedHashMap<>();
                logPayload.put("run_id", runId);
                logPayload.put("stage", "Pipeline");
                logPayload.put("direction", "info");
                logPayload.put("title", "运行失败");
                logPayload.put("payload", errorPayload);
                send(emitter, "log", logPayload);
                Map<String, Object> errorEvent = new LinkedHashMap<>();
                errorEvent.put("error", errorPayload.get("error"));
                errorEvent.put("code", "java_backend_error");
                errorEvent.put("run_id", runId);
                send(emitter, "error", errorEvent);
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("SSE 发送失败：" + e.getMessage(), e);
        }
    }
}
