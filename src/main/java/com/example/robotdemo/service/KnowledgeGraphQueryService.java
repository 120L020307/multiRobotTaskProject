package com.example.robotdemo.service;

import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;


import java.util.List;

@Service
public class KnowledgeGraphQueryService {

    private static final String CANDIDATE_QUERY = """
            MATCH (task:Task {uid: $taskUid})
                  -[:REQUIRES]->(capability:Capability)
            MATCH (task)-[:TARGETS]->(area:Area)

            OPTIONAL MATCH (task)-[:INFERRED_BY]->(rule:InferenceRule)
            OPTIONAL MATCH (rule)-[:REQUIRES_SENSOR]->(requiredSensor:Sensor)

            MATCH (robot:Robot)-[:HAS_CONTRACT]->(contract:Contract)
                  -[:IMPLEMENTS]->(capability)
            MATCH (contract)-[:EXPOSES]->(interface:RosInterface)

            OPTIONAL MATCH (robot)-[reachable:CAN_REACH]->(area)
            OPTIONAL MATCH (robot)-[equipped:EQUIPPED_WITH]->(requiredSensor)

            WITH task, capability, area, rule, requiredSensor,
                 robot, contract, interface,
                 robot.type = task.actor_type AS actorOk,
                 reachable IS NOT NULL AS areaOk,
                 requiredSensor IS NULL OR equipped IS NOT NULL AS sensorOk,
                 robot.online = true AS onlineOk,
                 robot.battery >= coalesce(contract.battery_min, 0)
                     AS batteryOk

            WITH *,
                 actorOk AND areaOk AND sensorOk AND onlineOk AND batteryOk
                     AS feasible,
                 CASE
                     WHEN NOT actorOk THEN 'ACTOR_TYPE_MISMATCH'
                     WHEN NOT onlineOk THEN 'OFFLINE'
                     WHEN NOT batteryOk THEN 'BATTERY_LOW'
                     WHEN NOT areaOk THEN 'AREA_UNREACHABLE'
                     WHEN NOT sensorOk THEN 'SENSOR_MISSING'
                     ELSE 'KEEP'
                 END AS reason

            RETURN task.uid AS taskUid,
                   robot.id AS robotId,
                   feasible,
                   reason,
                   capability.name AS matchedCapability,
                   requiredSensor.id AS requiredSensor,
                   rule.id AS inferenceRule,
                   area.id AS targetArea,
                   interface.name AS rosInterface,
                   robot.battery - contract.battery_min
                       AS batteryMargin
            ORDER BY feasible DESC, batteryMargin DESC
            """;

    private final Driver driver;

    public KnowledgeGraphQueryService(Driver driver) {
        this.driver = driver;
    }

    public List<KgCandidate> findCandidates(String taskUid) {
        try (var session = driver.session(
                SessionConfig.forDatabase("neo4j"))) {

            return session.executeRead(transaction ->
                    transaction.run(
                            CANDIDATE_QUERY,
                            Values.parameters("taskUid", taskUid)
                    ).list(record -> new KgCandidate(
                            record.get("taskUid").asString(),
                            record.get("robotId").asString(),
                            record.get("feasible").asBoolean(),
                            record.get("reason").asString(),
                            record.get("matchedCapability").asString(),
                            nullableString(record.get("requiredSensor")),
                            nullableString(record.get("inferenceRule")),
                            record.get("targetArea").asString(),
                            record.get("rosInterface").asString(),
                            record.get("batteryMargin").asLong()
                    ))
            );
        }
    }

    public List<String> findFeasibleRobotIds(String taskUid) {
        return findCandidates(taskUid).stream()
                .filter(KgCandidate::feasible)
                .map(KgCandidate::robotId)
                .distinct()
                .sorted()
                .toList();
    }

    private String nullableString(org.neo4j.driver.Value value) {
        return value.isNull() ? null : value.asString();
    }

    public record KgCandidate(
            String taskUid,
            String robotId,
            boolean feasible,
            String reason,
            String matchedCapability,
            String requiredSensor,
            String inferenceRule,
            String targetArea,
            String rosInterface,
            long batteryMargin
    ) {}
}
