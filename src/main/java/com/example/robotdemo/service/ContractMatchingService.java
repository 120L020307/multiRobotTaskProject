package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ContractMatchingService {
    private final ValidationService validationService;

    public ContractMatchingService(ValidationService validationService) {
        this.validationService = validationService;
    }

    public ExecutionPlan buildPlan(TaskGraph graph, List<RobotContract> contracts) {
        List<InterfaceBinding> bindings = new ArrayList<>();
        List<Map<String, Object>> unmapped = new ArrayList<>();
        for (TaskNode node : graph.nodes()) {
            if ("SYSTEM".equals(node.actor_type())) continue;
            List<ValidationService.Candidate> candidates = validationService.findCandidates(node, contracts, true);
            if (candidates.isEmpty()) {
                List<Map<String, Object>> loose = validationService.findCandidates(node, contracts, false).stream()
                        .map(c -> Map.<String, Object>of("robot_id", c.robot().robot_id(), "checks", c.checks()))
                        .toList();
                unmapped.add(Map.of("node_id", node.id(), "reason", loose.isEmpty() ? "无能力契约" : "候选机器人存在，但运行状态或参数不满足前置条件", "loose_candidates", loose));
                continue;
            }
            candidates.sort((a, b) -> Double.compare(score(node, b.robot()), score(node, a.robot())));
            ValidationService.Candidate chosen = candidates.get(0);
            Map<String, Object> params = new LinkedHashMap<>();
            chosen.contract().input_mapping().forEach((src, dst) -> params.put(dst, node.params().get(src)));
            bindings.add(new InterfaceBinding(node.id(), node.task_type(), chosen.robot().robot_id(), chosen.robot().robot_type(), score(node, chosen.robot()), chosen.contract().ros_interface(), params, chosen.checks()));
        }
        List<ScheduleStep> schedule = new ArrayList<>();
        List<String> order = topo(graph);
        for (int i = 0; i < order.size(); i++) {
            String nodeId = order.get(i);
            InterfaceBinding binding = bindings.stream().filter(b -> b.node_id().equals(nodeId)).findFirst().orElse(null);
            schedule.add(new ScheduleStep(i + 1, nodeId, binding));
        }
        return new ExecutionPlan(bindings, unmapped, schedule);
    }

    private double score(TaskNode node, RobotContract robot) {
        Object batteryValue = robot.state().getOrDefault("battery", 0);
        double battery = NumberSupport.toDouble(batteryValue, 0) / 100.0;
        double idle = Objects.equals(robot.state().get("status"), "idle") ? 1 : 0;
        return Math.round((0.6 * battery + 0.4 * idle) * 100.0) / 100.0;
    }

    private List<String> topo(TaskGraph graph) {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (TaskNode node : graph.nodes()) { indeg.put(node.id(), 0); adj.put(node.id(), new ArrayList<>()); }
        for (TaskEdge edge : graph.edges()) {
            if ("parallel".equals(edge.type())) continue;
            adj.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
            indeg.put(edge.target(), indeg.getOrDefault(edge.target(), 0) + 1);
        }
        Deque<String> queue = new ArrayDeque<>();
        indeg.forEach((id, degree) -> { if (degree == 0) queue.add(id); });
        List<String> out = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            out.add(id);
            for (String next : adj.getOrDefault(id, List.of())) {
                indeg.put(next, indeg.get(next) - 1);
                if (indeg.get(next) == 0) queue.add(next);
            }
        }
        return out.isEmpty() ? graph.nodes().stream().map(TaskNode::id).toList() : out;
    }
}
