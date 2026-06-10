package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CapabilityNormalizer {
    private static final double MIN_SCORE = 2.0;

    public TaskGraph normalize(TaskGraph graph, List<RobotContract> contracts) {
        if (graph == null || graph.nodes() == null || contracts == null || contracts.isEmpty()) return graph;
        List<TaskNode> nodes = graph.nodes().stream()
                .map(node -> normalizeNode(node, contracts))
                .collect(Collectors.toList());
        return GraphSupport.contractNormalize(new TaskGraph(nodes, graph.edges(), graph.constraints(), graph.raw_text(), null));
    }

    public String capabilityHint(List<RobotContract> contracts) {
        Map<String, Set<String>> byType = new TreeMap<>();
        for (RobotContract robot : contracts) {
            for (Contract contract : safeContracts(robot)) {
                byType.computeIfAbsent(robot.robot_type(), k -> new TreeSet<>()).add(contract.capability_name());
            }
        }
        return byType.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + String.join(", ", entry.getValue()))
                .collect(Collectors.joining("\n"));
    }

    public List<Map<String, Object>> candidates(TaskNode node, List<RobotContract> contracts, int limit) {
        if (node == null || contracts == null) return List.of();
        String query = queryText(node);
        return contracts.stream()
                .filter(robot -> Objects.equals(robot.robot_type(), node.actor_type()))
                .flatMap(robot -> safeContracts(robot).stream())
                .map(contract -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("capability_name", contract.capability_name());
                    row.put("score", score(contract, query, node.task_type()));
                    row.put("description", contract.description());
                    row.put("aliases", contract.aliases());
                    return row;
                })
                .sorted((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")))
                .limit(Math.max(1, limit))
                .toList();
    }

    private TaskNode normalizeNode(TaskNode node, List<RobotContract> contracts) {
        if (node == null || "SYSTEM".equals(node.actor_type())) return node;
        String normalized = bestCapability(node, contracts).orElse(node.task_type());
        if (Objects.equals(normalized, node.task_type())) return node;
        return new TaskNode(node.id(), normalized, node.actor_type(), node.params(), node.output(), node.status(), node.confidence(), node.missing_fields(), node.evidence());
    }

    private Optional<String> bestCapability(TaskNode node, List<RobotContract> contracts) {
        String actorType = node.actor_type();
        String query = queryText(node);
        CandidateScore best = null;
        for (RobotContract robot : contracts) {
            if (!Objects.equals(robot.robot_type(), actorType)) continue;
            for (Contract contract : safeContracts(robot)) {
                double score = score(contract, query, node.task_type());
                if (best == null || score > best.score()) best = new CandidateScore(contract.capability_name(), score);
            }
        }
        return best != null && best.score() >= MIN_SCORE ? Optional.of(best.capability()) : Optional.empty();
    }

    private double score(Contract contract, String query, String taskType) {
        String capability = norm(contract.capability_name());
        String task = norm(taskType);
        if (!capability.isBlank() && capability.equals(task)) return 100;
        double score = 0;
        if (!capability.isBlank() && containsToken(query, capability)) score = Math.max(score, 8);
        for (String alias : safeList(contract.aliases())) {
            String normalizedAlias = norm(alias);
            if (normalizedAlias.isBlank()) continue;
            if (normalizedAlias.equals(task)) score = Math.max(score, 12);
            if (containsToken(query, normalizedAlias)) score = Math.max(score, Math.min(10, Math.max(2, normalizedAlias.length() / 2.0)));
            score = Math.max(score, tokenOverlap(query, normalizedAlias));
        }
        String description = norm(contract.description());
        if (!description.isBlank()) score = Math.max(score, tokenOverlap(query, description));
        return score;
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
        return norm(String.join(" ", parts));
    }

    private boolean containsToken(String text, String token) {
        return text.contains(token);
    }

    private double tokenOverlap(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0;
        int hits = 0;
        for (String token : rightTokens) if (leftTokens.contains(token)) hits++;
        return hits * 1.0 / rightTokens.size();
    }

    private Set<String> tokens(String text) {
        return Arrays.stream(text.split("[^a-z0-9\\u4e00-\\u9fa5_]+"))
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Contract> safeContracts(RobotContract robot) {
        return robot == null || robot.contracts() == null ? List.of() : robot.contracts();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String norm(String value) {
        return GraphSupport.str(value).trim().toLowerCase(Locale.ROOT);
    }

    private record CandidateScore(String capability, double score) {}
}
