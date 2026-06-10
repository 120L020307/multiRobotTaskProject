package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ValidationService {
    public ValidationReport validate(TaskGraph graph, List<RobotContract> contracts) {
        List<ValidationIssue> errors = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();
        Set<String> ids = graph.nodes().stream().map(TaskNode::id).collect(Collectors.toSet());
        for (TaskNode node : graph.nodes()) {
            if (node.id() == null || node.id().isBlank()) errors.add(issue("missing_field", node.id(), null, "id", "error", "节点缺少 id"));
            if (node.task_type() == null || node.task_type().isBlank()) errors.add(issue("missing_field", node.id(), null, "task_type", "error", "节点缺少 task_type"));
            if (node.actor_type() == null || node.actor_type().isBlank()) errors.add(issue("missing_field", node.id(), null, "actor_type", "error", "节点缺少 actor_type"));
            if (node.evidence() == null || node.evidence().start() < 0) warnings.add(issue("missing_evidence", node.id(), null, null, "warning", "节点缺少可定位原文证据"));
            if (node.missing_fields() != null && !node.missing_fields().isEmpty()) errors.add(issue("missing_param", node.id(), null, String.join(",", node.missing_fields()), "error", "任务参数不完整"));
            if (!"SYSTEM".equals(node.actor_type()) && findCandidates(node, contracts, false).isEmpty()) {
                errors.add(issue("capability_or_contract_missing", node.id(), null, null, "error", "没有找到满足 " + node.task_type() + "/" + node.actor_type() + " 的能力契约"));
            }
        }
        for (TaskEdge edge : graph.edges()) {
            if (!ids.contains(edge.source()) || !ids.contains(edge.target())) errors.add(issue("invalid_dependency", null, edge.id(), null, "error", "依赖边引用了不存在的节点"));
            if ("condition".equals(edge.type()) && (edge.condition() == null || edge.condition().isBlank())) errors.add(issue("missing_condition", null, edge.id(), null, "error", "条件边缺少触发条件"));
        }
        if (hasCycle(graph)) errors.add(issue("cycle_detected", null, null, null, "error", "任务依赖图存在环"));
        List<String> scope = errors.stream().map(e -> e.node_id() != null ? e.node_id() : e.edge_id()).filter(Objects::nonNull).distinct().toList();
        return new ValidationReport(errors.isEmpty(), errors, warnings, scope, "发现 " + errors.size() + " 个错误，" + warnings.size() + " 个警告。");
    }

    public List<Candidate> findCandidates(TaskNode node, List<RobotContract> robots, boolean enforceState) {
        List<Candidate> out = new ArrayList<>();
        for (RobotContract robot : robots) {
            if (!Objects.equals(robot.robot_type(), node.actor_type())) continue;
            for (Contract contract : robot.contracts()) {
                if (!Objects.equals(contract.capability_name(), node.task_type())) continue;
                Map<String, Object> pre = contract.preconditions() == null ? Map.of() : contract.preconditions();
                Map<String, Object> state = robot.state() == null ? Map.of() : robot.state();
                boolean onlineOk = !pre.containsKey("online") || Objects.equals(state.get("online"), pre.get("online"));
                boolean batteryOk = !pre.containsKey("battery_min") || number(state.get("battery")) >= number(pre.get("battery_min"));
                Map<String, Object> params = node.params() == null ? Map.of() : node.params();
                Object area = params.get("area");
                Object reachableAreas = state.getOrDefault("reachable_areas", List.of());
                boolean reachableOk = area == null || String.valueOf(area).startsWith("${") || (reachableAreas instanceof List<?> list && list.contains(area));
                Map<String, String> inputMapping = contract.input_mapping() == null ? Map.of() : contract.input_mapping();
                boolean paramsOk = inputMapping.keySet().stream().allMatch(params::containsKey);
                boolean feasible = onlineOk && batteryOk && reachableOk && paramsOk;
                if (!enforceState || feasible) out.add(new Candidate(robot, contract, feasible, Map.of("onlineOk", onlineOk, "batteryOk", batteryOk, "reachableOk", reachableOk, "paramsOk", paramsOk)));
            }
        }
        return out;
    }

    private boolean hasCycle(TaskGraph graph) {
        Map<String, List<String>> adj = new HashMap<>();
        for (TaskNode node : graph.nodes()) adj.put(node.id(), new ArrayList<>());
        for (TaskEdge edge : graph.edges()) if (!"recovery".equals(edge.type())) adj.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : adj.keySet()) if (dfs(id, adj, visiting, visited)) return true;
        return false;
    }

    private boolean dfs(String id, Map<String, List<String>> adj, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) return true;
        if (visited.contains(id)) return false;
        visiting.add(id);
        for (String next : adj.getOrDefault(id, List.of())) if (dfs(next, adj, visiting, visited)) return true;
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private double number(Object value) {
        return NumberSupport.toDouble(value, 0);
    }

    private ValidationIssue issue(String code, String node, String edge, String field, String severity, String message) {
        return new ValidationIssue(code, node, edge, field, severity, message);
    }

    public record Candidate(RobotContract robot, Contract contract, boolean feasible, Map<String, Boolean> checks) {}
}
