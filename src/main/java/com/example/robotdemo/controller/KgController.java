package com.example.robotdemo.controller;

import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import com.example.robotdemo.service.KnowledgeGraphQueryService;
import com.example.robotdemo.model.Models.ManualGraphRequest;
import com.example.robotdemo.service.KnowledgeGraphSyncService;
import com.example.robotdemo.service.DataLoader;

@RestController
@RequestMapping("/api/kg")
public class KgController {

    private final Driver driver;
    private final KnowledgeGraphQueryService queryService;
    private final KnowledgeGraphSyncService syncService;
    private final DataLoader dataLoader;

    public KgController(
            Driver driver,
            KnowledgeGraphQueryService queryService,
            KnowledgeGraphSyncService syncService,
            DataLoader dataLoader
    ) {
        this.driver = driver;
        this.queryService = queryService;
        this.syncService = syncService;
        this.dataLoader = dataLoader;
    }

    @PostMapping("/sync-resources")
    public ResponseEntity<?> syncResources() {
        try {
            var result = syncService.syncStaticResources(
                    dataLoader.contracts(),
                    dataLoader.virtualRobotSensors()
            );
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "robot_count", result.robotCount(),
                    "capability_count", result.capabilityCount(),
                    "contract_count", result.contractCount(),
                    "area_count", result.areaCount(),
                    "sensor_count", result.sensorCount()
            ));
        } catch (Exception exception) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "ok", false,
                            "message", String.valueOf(exception.getMessage())
                    ));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            driver.verifyConnectivity();

            try (var session = driver.session(
                    SessionConfig.forDatabase("neo4j"))) {

                var result = session.run("""
                        MATCH (robot:Robot)
                        OPTIONAL MATCH (contract:Contract)
                        OPTIONAL MATCH (capability:Capability)
                        RETURN count(DISTINCT robot) AS robots,
                               count(DISTINCT contract) AS contracts,
                               count(DISTINCT capability) AS capabilities
                        """).single();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("ok", true);
                response.put("database", "neo4j");
                response.put("robots", result.get("robots").asLong());
                response.put("contracts", result.get("contracts").asLong());
                response.put(
                        "capabilities",
                        result.get("capabilities").asLong()
                );

                return ResponseEntity.ok(response);
            }
        } catch (Exception exception) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", false);
            response.put("error", exception.getClass().getSimpleName());
            response.put(
                    "message",
                    String.valueOf(exception.getMessage())
            );

            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(response);
        }
    }

    @GetMapping("/candidates")
    public ResponseEntity<?> candidates(
            @RequestParam String taskUid
    ) {
        try {
            var candidates = queryService.findCandidates(taskUid);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("task_uid", taskUid);
            response.put("candidate_count", candidates.size());
            response.put(
                    "feasible_count",
                    candidates.stream()
                            .filter(candidate -> candidate.feasible())
                            .count()
            );
            response.put("candidates", candidates);

            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "ok", false,
                            "message", String.valueOf(exception.getMessage())
                    ));
        }
    }
    @PostMapping("/sync-manual")
    public ResponseEntity<?> syncManual(
            @RequestBody ManualGraphRequest request
    ) {
        try {
            String runId = "java-manual-" + System.currentTimeMillis();

            var result = syncService.syncTaskGraph(
                    runId,
                    request.graph()
            );

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "run_id", result.runId(),
                    "node_count", result.nodeCount(),
                    "edge_count", result.edgeCount()
            ));
        } catch (Exception exception) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "ok", false,
                            "message",
                            String.valueOf(exception.getMessage())
                    ));
        }
    }
}
