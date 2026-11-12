package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.RobotContract;
import com.example.robotdemo.model.Models.TaskGraph;
import com.example.robotdemo.model.Models.TaskNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class KnowledgeGraphShadowService {
    private final KnowledgeGraphSyncService syncService;
    private final KnowledgeGraphQueryService queryService;
    private final ValidationService validationService;
    private final String mode;

    public KnowledgeGraphShadowService(
            KnowledgeGraphSyncService syncService,
            KnowledgeGraphQueryService queryService,
            ValidationService validationService,
            @Value("${kg.mode:off}") String mode
    ) {
        this.syncService = syncService;
        this.queryService = queryService;
        this.validationService = validationService;
        this.mode = mode == null ? "off" : mode.trim().toLowerCase();
    }

    public ShadowReport compare(
            String runId,
            TaskGraph graph,
            List<RobotContract> contracts
    ) {
        if ("off".equals(mode)) {
            return new ShadowReport(
                    mode,
                    "skipped",
                    runId,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    null
            );
        }

        try {
            KnowledgeGraphSyncService.SyncResult sync = syncService.syncTaskGraph(runId, graph);
            List<NodeComparison> comparisons = new ArrayList<>();

            for (TaskNode node : graph.nodes()) {
                if ("SYSTEM".equals(node.actor_type())) continue;

                List<String> ruleCandidates = validationService
                        .findCandidates(node, contracts, true)
                        .stream()
                        .map(candidate -> candidate.robot().robot_id())
                        .distinct()
                        .sorted()
                        .toList();

                String taskUid = runId + ":" + node.id();
                List<String> kgCandidates = queryService.findFeasibleRobotIds(taskUid);
                Set<String> ruleSet = new LinkedHashSet<>(ruleCandidates);
                Set<String> kgSet = new LinkedHashSet<>(kgCandidates);

                Set<String> onlyInRules = new LinkedHashSet<>(ruleSet);
                onlyInRules.removeAll(kgSet);
                Set<String> onlyInKg = new LinkedHashSet<>(kgSet);
                onlyInKg.removeAll(ruleSet);

                comparisons.add(new NodeComparison(
                        node.id(),
                        taskUid,
                        node.task_type(),
                        ruleCandidates,
                        kgCandidates,
                        new ArrayList<>(onlyInRules),
                        new ArrayList<>(onlyInKg),
                        Objects.equals(ruleSet, kgSet),
                        classifyDifference(ruleSet, kgSet)
                ));
            }

            long mismatchCount = comparisons.stream()
                    .filter(comparison -> !comparison.match())
                    .count();
            long refinementCount = comparisons.stream()
                    .filter(comparison -> "KG_REFINEMENT".equals(comparison.differenceType()))
                    .count();
            long unexpectedDifferenceCount = comparisons.stream()
                    .filter(comparison -> "KG_EXPANSION".equals(comparison.differenceType())
                            || "CONFLICT".equals(comparison.differenceType()))
                    .count();
            return new ShadowReport(
                    mode,
                    "completed",
                    runId,
                    sync.nodeCount(),
                    mismatchCount,
                    refinementCount,
                    unexpectedDifferenceCount,
                    comparisons,
                    null
            );
        } catch (Exception exception) {
            return new ShadowReport(
                    mode,
                    "failed_open",
                    runId,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    public String mode() {
        return mode;
    }

    private String classifyDifference(Set<String> ruleSet, Set<String> kgSet) {
        if (Objects.equals(ruleSet, kgSet)) return "EQUIVALENT";
        if (ruleSet.containsAll(kgSet)) return "KG_REFINEMENT";
        if (kgSet.containsAll(ruleSet)) return "KG_EXPANSION";
        return "CONFLICT";
    }

    public record NodeComparison(
            String nodeId,
            String taskUid,
            String taskType,
            List<String> ruleCandidates,
            List<String> kgCandidates,
            List<String> onlyInRules,
            List<String> onlyInKg,
            boolean match,
            String differenceType
    ) {}

    public record ShadowReport(
            String mode,
            String status,
            String runId,
            int syncedNodeCount,
            long mismatchCount,
            long refinementCount,
            long unexpectedDifferenceCount,
            List<NodeComparison> comparisons,
            String error
    ) {}
}
