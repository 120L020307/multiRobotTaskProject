package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RecoveryService {
    private final ContractMatchingService contractMatchingService;

    public RecoveryService(ContractMatchingService contractMatchingService) {
        this.contractMatchingService = contractMatchingService;
    }

    public RecoveryPlan recover(TaskGraph graph, ExecutionPlan plan, ExecutionEvent event, List<RobotContract> contracts, RecoveryStrategy strategy) {
        String failedNode = event.task_id() != null ? event.task_id() : plan.bindings().stream().filter(b -> b.robot_id().equals(event.robot_id())).map(InterfaceBinding::node_id).findFirst().orElse(null);
        List<String> affected = searchAffected(graph, failedNode);
        Set<String> completed = new LinkedHashSet<>(event.completed_tasks() == null ? List.of() : event.completed_tasks());
        List<String> frozen = graph.nodes().stream().filter(n -> !affected.contains(n.id()) || completed.contains(n.id())).map(TaskNode::id).toList();
        InterfaceBinding original = plan.bindings().stream().filter(b -> b.node_id().equals(failedNode)).findFirst().orElse(null);
        List<RobotContract> updated = contracts.stream().map(robot -> {
            if (!robot.robot_id().equals(event.robot_id())) return robot;
            Map<String, Object> state = new LinkedHashMap<>(robot.state());
            state.put("battery", Math.min(number(state.get("battery")), 10));
            state.put("status", "blocked_by_event");
            return new RobotContract(robot.robot_id(), robot.robot_type(), robot.capabilities(), state, robot.contracts());
        }).toList();
        TaskGraph sub = new TaskGraph(
                graph.nodes().stream().filter(n -> affected.contains(n.id())).toList(),
                graph.edges().stream().filter(e -> affected.contains(e.source()) && affected.contains(e.target())).toList(),
                graph.constraints(), graph.raw_text(), graph.evidence_coverage()
        );
        ExecutionPlan newPlan = contractMatchingService.buildPlan(sub, updated);
        InterfaceBinding replacement = newPlan.bindings().stream().filter(b -> b.node_id().equals(failedNode) && !b.robot_id().equals(event.robot_id())).findFirst().orElse(null);
        List<String> changed = newPlan.bindings().stream().filter(b -> {
            InterfaceBinding old = plan.bindings().stream().filter(x -> x.node_id().equals(b.node_id())).findFirst().orElse(null);
            return old == null || !old.robot_id().equals(b.robot_id()) || !old.ros_interface().name().equals(b.ros_interface().name());
        }).map(InterfaceBinding::node_id).toList();
        int cost = changed.size() * 2 + (replacement != null ? 3 : 0) + Math.max(0, affected.size() - changed.size());
        String explanation = replacement != null
                ? "事件 " + event.event_type() + " 导致 " + event.robot_id() + " 不满足前置条件，系统仅重绑定受影响节点 " + String.join("、", changed) + "，冻结 " + String.join("、", frozen) + "。"
                : "未找到替换机器人，需要人工介入或全局重规划。";
        return new RecoveryPlan(event, strategy, failedNode, original, affected, frozen, changed, replacement == null ? null : replacement.robot_id(), newPlan.bindings(), cost, explanation);
    }

    private List<String> searchAffected(TaskGraph graph, String start) {
        if (start == null || start.isBlank()) return List.of();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (TaskNode node : graph.nodes()) adj.put(node.id(), new ArrayList<>());
        for (TaskEdge edge : graph.edges()) if (!"recovery".equals(edge.type())) adj.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        List<String> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (out.contains(id)) continue;
            out.add(id);
            queue.addAll(adj.getOrDefault(id, List.of()));
        }
        return out;
    }

    private int number(Object value) {
        return NumberSupport.toInt(value, 0);
    }
}
