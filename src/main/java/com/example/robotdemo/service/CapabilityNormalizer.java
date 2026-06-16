package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CapabilityNormalizer {
    private final NormalizationRuleService rules;

    public CapabilityNormalizer(NormalizationRuleService rules) {
        this.rules = rules;
    }

    public TaskGraph normalize(TaskGraph graph, List<RobotContract> contracts) {
        TaskGraph actorNormalized = normalizeNodes(graph, contracts);
        TaskGraph folded = foldSystemDetectionNodes(actorNormalized);
        TaskGraph completed = completeParams(folded, contracts);
        return GraphSupport.withCoverage(completed);
    }

    public String capabilityHint(List<RobotContract> contracts) {
        Map<String, Set<String>> byActor = new LinkedHashMap<>();
        for (RobotContract robot : contracts == null ? List.<RobotContract>of() : contracts) {
            String actor = rules.normalizeActorType(robot.robot_type());
            for (Contract contract : robot.contracts() == null ? List.<Contract>of() : robot.contracts()) {
                String aliases = contract.aliases() == null || contract.aliases().isEmpty() ? "" : " aliases=" + contract.aliases();
                byActor.computeIfAbsent(actor, k -> new LinkedHashSet<>()).add(contract.capability_name() + aliases);
            }
        }
        return byActor.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + String.join("; ", entry.getValue()))
                .collect(Collectors.joining("\n"));
    }

    public List<Map<String, Object>> candidates(TaskNode node, List<RobotContract> contracts, int limit) {
        String query = queryText(node);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RobotContract robot : contracts == null ? List.<RobotContract>of() : contracts) {
            if (!Objects.equals(rules.normalizeActorType(robot.robot_type()), rules.normalizeActorType(node.actor_type()))) continue;
            for (Contract contract : robot.contracts() == null ? List.<Contract>of() : robot.contracts()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("robot_id", robot.robot_id());
                row.put("capability_name", contract.capability_name());
                row.put("score", score(contract, query, node.task_type()));
                row.put("description", contract.description());
                row.put("aliases", contract.aliases());
                rows.add(row);
            }
        }
        return rows.stream()
                .sorted((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")))
                .limit(Math.max(1, limit))
                .toList();
    }

    private TaskGraph normalizeNodes(TaskGraph graph, List<RobotContract> contracts) {
        List<TaskNode> nodes = graph.nodes().stream().map(node -> {
            String actorType = rules.normalizeActorType(node.actor_type());
            TaskNode actorNode = new TaskNode(node.id(), node.task_type(), actorType, node.params(), node.output(), node.status(), node.confidence(), node.missing_fields(), node.evidence());
            String taskType = rules.normalizeCapability(actorNode, contracts);
            Contract contract = rules.contractFor(actorType, taskType, contracts).orElse(null);
            Map<String, Object> params = rules.normalizeParamKeys(node.params(), contract);
            EvidenceSpan evidence = node.evidence();
            if (evidence == null || evidence.start() < 0) {
                evidence = rules.inferEvidence(graph.raw_text(), new TaskNode(node.id(), taskType, actorType, params, node.output(), node.status(), node.confidence(), node.missing_fields(), node.evidence()), contract);
            }
            return new TaskNode(node.id(), taskType, actorType, params, node.output(), node.status(), node.confidence(), node.missing_fields(), evidence);
        }).toList();
        List<TaskEdge> edges = graph.edges().stream().map(edge -> new TaskEdge(
                edge.id(),
                edge.source(),
                edge.target(),
                rules.normalizeEdgeType(edge.type(), edge.evidence(), edge.condition()),
                edge.condition(),
                edge.evidence()
        )).toList();
        return new TaskGraph(nodes, edges, graph.constraints(), graph.raw_text(), null);
    }

    private TaskGraph completeParams(TaskGraph graph, List<RobotContract> contracts) {
        List<TaskNode> nodes = graph.nodes().stream().map(node -> {
            if ("SYSTEM".equals(node.actor_type())) return node;
            Contract contract = rules.contractFor(node.actor_type(), node.task_type(), contracts).orElse(null);
            Map<String, Object> params = rules.applyDefaultsAndDerived(node.params(), node, contract, graph);
            List<String> missing = rules.remainingMissingFields(node.missing_fields(), params, contract);
            return new TaskNode(node.id(), node.task_type(), node.actor_type(), params, node.output(), node.status(), node.confidence(), missing, node.evidence());
        }).toList();
        return new TaskGraph(nodes, graph.edges(), graph.constraints(), graph.raw_text(), null);
    }

    private TaskGraph foldSystemDetectionNodes(TaskGraph graph) {
        Map<String, TaskNode> byId = graph.nodes().stream().collect(Collectors.toMap(TaskNode::id, node -> node, (a, b) -> a, LinkedHashMap::new));
        Set<String> foldedIds = graph.nodes().stream()
                .filter(rules::isSystemDetectionNode)
                .map(TaskNode::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (foldedIds.isEmpty()) return graph;

        List<TaskNode> nodes = graph.nodes().stream().filter(node -> !foldedIds.contains(node.id())).collect(Collectors.toList());
        Map<String, List<TaskEdge>> incoming = new LinkedHashMap<>();
        Map<String, List<TaskEdge>> outgoing = new LinkedHashMap<>();
        for (TaskEdge edge : graph.edges()) {
            if (foldedIds.contains(edge.target())) incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
            if (foldedIds.contains(edge.source())) outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
        }

        List<TaskEdge> edges = graph.edges().stream()
                .filter(edge -> !foldedIds.contains(edge.source()) && !foldedIds.contains(edge.target()))
                .collect(Collectors.toCollection(ArrayList::new));
        Set<String> edgeKeys = edges.stream().map(edge -> edge.source() + "->" + edge.target() + ":" + edge.type()).collect(Collectors.toCollection(LinkedHashSet::new));
        int generated = 1;
        for (String systemId : foldedIds) {
            TaskNode systemNode = byId.get(systemId);
            List<TaskEdge> ins = incoming.getOrDefault(systemId, List.of());
            List<TaskEdge> outs = outgoing.getOrDefault(systemId, List.of());
            for (TaskEdge in : ins) {
                for (TaskEdge out : outs) {
                    if (foldedIds.contains(in.source()) || foldedIds.contains(out.target()) || Objects.equals(in.source(), out.target())) continue;
                    String edgeType = rules.foldedEdgeType(systemNode);
                    String key = in.source() + "->" + out.target() + ":" + edgeType;
                    if (!edgeKeys.add(key)) continue;
                    edges.add(new TaskEdge(
                            "E_SYS_" + generated++,
                            in.source(),
                            out.target(),
                            edgeType,
                            rules.foldedCondition(systemNode, out.condition(), in.condition()),
                            bestEvidence(systemNode.evidence(), out.evidence(), in.evidence())
                    ));
                }
            }
        }
        return new TaskGraph(nodes, edges, graph.constraints(), graph.raw_text(), null);
    }

    private EvidenceSpan bestEvidence(EvidenceSpan... spans) {
        for (EvidenceSpan span : spans) if (span != null && span.start() >= 0) return span;
        return new EvidenceSpan("", -1, -1);
    }

    private String queryText(TaskNode node) {
        List<String> parts = new ArrayList<>();
        parts.add(node.task_type());
        parts.add(node.actor_type());
        if (node.evidence() != null) parts.add(node.evidence().text());
        if (node.params() != null) node.params().forEach((key, value) -> {
            parts.add(key);
            parts.add(String.valueOf(value));
        });
        return GraphSupport.str(String.join(" ", parts)).trim().toLowerCase(Locale.ROOT);
    }

    private double score(Contract contract, String query, String taskType) {
        String capability = GraphSupport.str(contract.capability_name()).trim().toLowerCase(Locale.ROOT);
        String task = GraphSupport.str(taskType).trim().toLowerCase(Locale.ROOT);
        if (!capability.isBlank() && capability.equals(task)) return 100;
        double score = 0;
        if (!capability.isBlank() && query.contains(capability)) score = Math.max(score, 8);
        for (String alias : contract.aliases() == null ? List.<String>of() : contract.aliases()) {
            String normalizedAlias = GraphSupport.str(alias).trim().toLowerCase(Locale.ROOT);
            if (normalizedAlias.isBlank()) continue;
            if (normalizedAlias.equals(task)) score = Math.max(score, 12);
            if (query.contains(normalizedAlias)) score = Math.max(score, Math.min(10, Math.max(2, normalizedAlias.length() / 2.0)));
        }
        return score;
    }
}
