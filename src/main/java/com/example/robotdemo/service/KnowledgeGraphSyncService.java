package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.TaskEdge;
import com.example.robotdemo.model.Models.TaskGraph;
import com.example.robotdemo.model.Models.TaskNode;
import com.example.robotdemo.model.Models.Contract;
import com.example.robotdemo.model.Models.RobotContract;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

@Service
public class KnowledgeGraphSyncService {

    private final Driver driver;

    public KnowledgeGraphSyncService(Driver driver) {
        this.driver = driver;
    }

    public SyncResult syncTaskGraph(String runId, TaskGraph graph) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (graph == null || graph.nodes() == null) {
            throw new IllegalArgumentException("TaskGraph 不能为空");
        }

        try (var session = driver.session(
                SessionConfig.forDatabase("neo4j"))) {

            return session.executeWrite(tx -> {
                tx.run("""
                        MERGE (run:Run {uid: $runId})
                        SET run.raw_text = $rawText,
                            run.data_source = 'java_runtime'
                        """,
                        Values.parameters(
                                "runId", runId,
                                "rawText",
                                graph.raw_text() == null
                                        ? ""
                                        : graph.raw_text()
                        )
                ).consume();

                int nodeCount = 0;
                for (TaskNode node : graph.nodes()) {
                    String taskUid = runId + ":" + node.id();

                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("runId", runId);
                    params.put("taskUid", taskUid);
                    params.put("nodeId", node.id());
                    params.put("taskType", node.task_type());
                    params.put("actorType", node.actor_type());
                    params.put("status", node.status());
                    params.put("confidence", node.confidence());
                    params.put(
                            "evidenceText",
                            node.evidence() == null
                                    ? ""
                                    : node.evidence().text()
                    );
                    params.put(
                            "paramsText",
                            String.valueOf(node.params())
                    );

                    tx.run("""
                            MATCH (run:Run {uid: $runId})
                            MERGE (task:Task {uid: $taskUid})
                            SET task.run_id = $runId,
                                task.node_id = $nodeId,
                                task.task_type = $taskType,
                                task.actor_type = $actorType,
                                task.status = $status,
                                task.confidence = $confidence,
                                task.evidence_text = $evidenceText,
                                task.params_text = $paramsText
                            MERGE (run)-[:HAS_TASK]->(task)
                            """,
                            params
                    ).consume();

                    tx.run("""
                            MATCH (task:Task {uid: $taskUid})
                            MERGE (capability:Capability {name: $taskType})
                            MERGE (task)-[:REQUIRES]->(capability)
                            """,
                            Values.parameters(
                                    "taskUid", taskUid,
                                    "taskType",
                                    node.task_type() == null
                                            ? "unknown"
                                            : node.task_type()
                            )
                    ).consume();

                    Object area = node.params() == null
                            ? null
                            : node.params().get("area");

                    if (area != null && !String.valueOf(area).isBlank()) {
                        tx.run("""
                                MATCH (task:Task {uid: $taskUid})
                                MERGE (area:Area {id: $areaId})
                                MERGE (task)-[:TARGETS]->(area)
                                """,
                                Values.parameters(
                                        "taskUid", taskUid,
                                        "areaId", String.valueOf(area)
                                )
                        ).consume();
                    }

                    inferKnowledgeRequirements(tx, taskUid, node);

                    nodeCount++;
                }

                int edgeCount = 0;
                for (TaskEdge edge : graph.edges() == null ? java.util.List.<TaskEdge>of() : graph.edges()) {
                    String sourceUid = runId + ":" + edge.source();
                    String targetUid = runId + ":" + edge.target();

                    tx.run("""
                            MATCH (source:Task {uid: $sourceUid})
                            MATCH (target:Task {uid: $targetUid})
                            MERGE (target)-[dependency:DEPENDS_ON {
                                id: $edgeId
                            }]->(source)
                            SET dependency.type = $edgeType,
                                dependency.condition = $condition,
                                dependency.evidence_text = $evidenceText
                            """,
                            Values.parameters(
                                    "sourceUid", sourceUid,
                                    "targetUid", targetUid,
                                    "edgeId", runId + ":" + edge.id(),
                                    "edgeType", edge.type(),
                                    "condition",
                                    edge.condition() == null
                                            ? ""
                                            : edge.condition(),
                                    "evidenceText",
                                    edge.evidence() == null
                                            ? ""
                                            : edge.evidence().text()
                            )
                    ).consume();

                    edgeCount++;
                }

                return new SyncResult(runId, nodeCount, edgeCount);
            });
        }
    }

    public ResourceSyncResult syncStaticResources(
            List<RobotContract> robots,
            List<Map<String, Object>> sensorConfig
    ) {
        Map<String, List<String>> sensorsByRobot = new LinkedHashMap<>();
        for (Map<String, Object> row : sensorConfig == null ? List.<Map<String, Object>>of() : sensorConfig) {
            String robotId = String.valueOf(row.getOrDefault("robot_id", ""));
            Object sensors = row.get("sensors");
            List<String> sensorIds = sensors instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            sensorsByRobot.put(robotId, sensorIds);
        }

        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            return session.executeWrite(tx -> {
                int contractCount = 0;
                Set<String> capabilityNames = new LinkedHashSet<>();
                Set<String> areaIds = new LinkedHashSet<>();
                Set<String> sensorIds = new LinkedHashSet<>();

                for (RobotContract robot : robots == null ? List.<RobotContract>of() : robots) {
                    Map<String, Object> state = robot.state() == null ? Map.of() : robot.state();
                    tx.run("""
                            MERGE (robot:Robot {id: $robotId})
                            SET robot.type = $robotType,
                                robot.online = $online,
                                robot.battery = $battery,
                                robot.status = $status,
                                robot.data_source = 'contracts.json'
                            """, Values.parameters(
                            "robotId", robot.robot_id(),
                            "robotType", robot.robot_type(),
                            "online", Boolean.TRUE.equals(state.get("online")),
                            "battery", NumberSupport.toInt(state.get("battery"), 0),
                            "status", String.valueOf(state.getOrDefault("status", "unknown"))
                    )).consume();

                    for (String capabilityName : robot.capabilities() == null ? List.<String>of() : robot.capabilities()) {
                        capabilityNames.add(capabilityName);
                        tx.run("""
                                MATCH (robot:Robot {id: $robotId})
                                MERGE (capability:Capability {name: $capability})
                                MERGE (robot)-[has:HAS_CAPABILITY]->(capability)
                                """, Values.parameters(
                                "robotId", robot.robot_id(),
                                "capability", capabilityName
                        )).consume();
                    }

                    Object reachable = state.get("reachable_areas");
                    if (reachable instanceof List<?> areas) {
                        for (Object areaValue : areas) {
                            String areaId = String.valueOf(areaValue);
                            areaIds.add(areaId);
                            tx.run("""
                                    MATCH (robot:Robot {id: $robotId})
                                    MERGE (area:Area {id: $areaId})
                                    ON CREATE SET area.data_source = 'contracts.json'
                                    MERGE (robot)-[:CAN_REACH]->(area)
                                    """, Values.parameters(
                                    "robotId", robot.robot_id(),
                                    "areaId", areaId
                            )).consume();
                        }
                    }

                    for (String sensorId : sensorsByRobot.getOrDefault(robot.robot_id(), List.of())) {
                        sensorIds.add(sensorId);
                        tx.run("""
                                MATCH (robot:Robot {id: $robotId})
                                MERGE (sensor:Sensor {id: $sensorId})
                                SET sensor.data_source = 'virtual_robot_sensors.json'
                                MERGE (robot)-[:EQUIPPED_WITH]->(sensor)
                                """, Values.parameters(
                                "robotId", robot.robot_id(),
                                "sensorId", sensorId
                        )).consume();
                    }

                    for (Contract contract : robot.contracts() == null ? List.<Contract>of() : robot.contracts()) {
                        contractCount++;
                        capabilityNames.add(contract.capability_name());
                        String contractId = robot.robot_id() + ":" + contract.capability_name();
                        Map<String, Object> preconditions = contract.preconditions() == null
                                ? Map.of()
                                : contract.preconditions();
                        int batteryMin = NumberSupport.toInt(preconditions.get("battery_min"), 0);
                        boolean onlineRequired = !preconditions.containsKey("online")
                                || Boolean.TRUE.equals(preconditions.get("online"));
                        List<String> inputFields = contract.input_mapping() == null
                                ? List.of()
                                : new ArrayList<>(contract.input_mapping().keySet());
                        List<String> outputFields = contract.output_mapping() == null
                                ? List.of()
                                : new ArrayList<>(contract.output_mapping().values());

                        tx.run("""
                                MATCH (robot:Robot {id: $robotId})
                                MERGE (capability:Capability {name: $capability})
                                MERGE (contract:Contract {id: $contractId})
                                SET contract.battery_min = $batteryMin,
                                    contract.online_required = $onlineRequired,
                                    contract.input_fields = $inputFields,
                                    contract.failure_events = $failureEvents,
                                    contract.data_source = 'contracts.json'
                                MERGE (robot)-[:HAS_CONTRACT]->(contract)
                                MERGE (contract)-[:IMPLEMENTS]->(capability)
                                WITH robot, capability, contract
                                MATCH (robot)-[has:HAS_CAPABILITY]->(capability)
                                SET has.kind = 'executable', has.requires_contract = true
                                """, Values.parameters(
                                "robotId", robot.robot_id(),
                                "capability", contract.capability_name(),
                                "contractId", contractId,
                                "batteryMin", batteryMin,
                                "onlineRequired", onlineRequired,
                                "inputFields", inputFields,
                                "failureEvents", contract.failure_events() == null ? List.of() : contract.failure_events()
                        )).consume();

                        if (contract.ros_interface() != null
                                && contract.ros_interface().name() != null
                                && !contract.ros_interface().name().isBlank()) {
                            String interfaceId = contract.ros_interface().type()
                                    + ":" + contract.ros_interface().name();
                            tx.run("""
                                    MATCH (contract:Contract {id: $contractId})
                                    MERGE (interface:RosInterface {id: $interfaceId})
                                    SET interface.type = $interfaceType,
                                        interface.name = $interfaceName,
                                        interface.input_fields = $inputFields,
                                        interface.output_fields = $outputFields,
                                        interface.data_source = 'contracts.json'
                                    MERGE (contract)-[:EXPOSES]->(interface)
                                    """, Values.parameters(
                                    "contractId", contractId,
                                    "interfaceId", interfaceId,
                                    "interfaceType", contract.ros_interface().type(),
                                    "interfaceName", contract.ros_interface().name(),
                                    "inputFields", inputFields,
                                    "outputFields", outputFields
                            )).consume();
                        }
                    }
                }

                tx.run("""
                        MATCH (robot:Robot)-[has:HAS_CAPABILITY]->(capability:Capability {name: 'detect_anomaly'})
                        SET has.kind = 'internal',
                            has.requires_contract = false,
                            has.implementation = 'generated_executor.detect_anomaly',
                            capability.category = 'perception',
                            capability.execution_mode = 'internal_function'
                        """).consume();

                tx.run("""
                        MATCH (capability:Capability {name: 'detect_anomaly'})
                        MATCH (sensor:Sensor)
                        WHERE sensor.id IN ['rgb_camera', 'thermal_camera']
                        MERGE (capability)-[:SUPPORTED_BY]->(sensor)
                        """).consume();

                return new ResourceSyncResult(
                        robots == null ? 0 : robots.size(),
                        capabilityNames.size(),
                        contractCount,
                        areaIds.size(),
                        sensorIds.size()
                );
            });
        }
    }

    private void inferKnowledgeRequirements(
            org.neo4j.driver.TransactionContext tx,
            String taskUid,
            TaskNode node
    ) {
        if (!"inspect_area".equals(node.task_type())) return;

        String evidence = node.evidence() == null ? "" : node.evidence().text();
        String text = evidence;
        Object mode = node.params() == null ? null : node.params().get("mode");
        boolean night = containsAny(text, "夜间", "夜晚", "night");
        boolean thermal = containsAny(text, "热成像", "红外", "thermal", "infrared")
                || containsAny(String.valueOf(mode), "热成像", "红外", "thermal", "infrared");
        if (!night || !thermal) return;

        tx.run("""
                MATCH (task:Task {uid: $taskUid})
                MATCH (capability:Capability {name: 'inspect_area'})
                MERGE (context:Context {id: 'time:night'})
                SET context.key = 'time', context.value = 'night'
                MERGE (sensor:Sensor {id: 'thermal_camera'})
                SET sensor.type = 'thermal', sensor.supports_low_light = true
                MERGE (rule:InferenceRule {id: 'night_thermal_inspection'})
                SET rule.name = '夜间热成像巡检规则',
                    rule.enabled = true,
                    rule.priority = 10,
                    rule.data_source = 'java_runtime_rule'
                MERGE (rule)-[:WHEN_CONTEXT]->(context)
                MERGE (rule)-[:APPLIES_TO]->(capability)
                MERGE (rule)-[:REQUIRES_SENSOR]->(sensor)
                MERGE (task)-[:HAS_CONTEXT]->(context)
                MERGE (task)-[:INFERRED_BY]->(rule)
                MERGE (task)-[requirement:REQUIRES_SENSOR]->(sensor)
                SET requirement.source = 'inference_rule',
                    requirement.rule_id = rule.id,
                    requirement.evidence = $evidence
                """,
                Values.parameters("taskUid", taskUid, "evidence", evidence)
        ).consume();
    }

    private boolean containsAny(String value, String... keywords) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    public record SyncResult(
            String runId,
            int nodeCount,
            int edgeCount
    ) {}

    public record ResourceSyncResult(
            int robotCount,
            int capabilityCount,
            int contractCount,
            int areaCount,
            int sensorCount
    ) {}
}
