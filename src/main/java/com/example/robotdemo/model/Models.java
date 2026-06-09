package com.example.robotdemo.model;

import java.util.*;

public final class Models {
    private Models() {}

    public record PipelineRequest(String text, ExecutionEvent event) {}

    public record EvidenceSpan(String text, int start, int end) {}

    public record TaskIntent(
            String goal,
            Map<String, Boolean> actors,
            List<String> objects,
            List<String> areas,
            List<Map<String, Object>> conditions,
            String raw_text
    ) {}

    public record TaskNode(
            String id,
            String task_type,
            String actor_type,
            Map<String, Object> params,
            Map<String, Object> output,
            String status,
            double confidence,
            List<String> missing_fields,
            EvidenceSpan evidence
    ) {}

    public record TaskEdge(
            String id,
            String source,
            String target,
            String type,
            String condition,
            EvidenceSpan evidence
    ) {}

    public record TaskConstraint(
            String type,
            Object value,
            String target_actor,
            EvidenceSpan evidence
    ) {}

    public record EvidenceCoverage(
            int nodes_with_evidence,
            int total_nodes,
            int edges_with_evidence,
            int total_edges,
            double ratio
    ) {}

    public record TaskGraph(
            List<TaskNode> nodes,
            List<TaskEdge> edges,
            List<TaskConstraint> constraints,
            String raw_text,
            EvidenceCoverage evidence_coverage
    ) {}

    public record ValidationIssue(
            String code,
            String node_id,
            String edge_id,
            String field,
            String severity,
            String message
    ) {}

    public record ValidationReport(
            boolean overall_valid,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            List<String> repair_scope,
            String summary
    ) {}

    public record RosInterface(String type, String name) {}

    public record Contract(
            String capability_name,
            RosInterface ros_interface,
            Map<String, String> input_mapping,
            Map<String, String> output_mapping,
            Map<String, Object> preconditions,
            List<String> failure_events
    ) {}

    public record RobotContract(
            String robot_id,
            String robot_type,
            List<String> capabilities,
            Map<String, Object> state,
            List<Contract> contracts
    ) {}

    public record InterfaceBinding(
            String node_id,
            String task_type,
            String robot_id,
            String robot_type,
            double score,
            RosInterface ros_interface,
            Map<String, Object> interface_params,
            Map<String, Boolean> precondition_checks
    ) {}

    public record ScheduleStep(int order, String node_id, InterfaceBinding binding) {}

    public record ExecutionPlan(
            List<InterfaceBinding> bindings,
            List<Map<String, Object>> unmapped,
            List<ScheduleStep> schedule
    ) {}

    public record ExecutionEvent(
            String event_type,
            String robot_id,
            String task_id,
            String interface_name,
            List<String> completed_tasks,
            String message
    ) {}

    public record RecoveryStrategy(
            String strategy,
            String reasoning_summary,
            List<String> preferred_replacement_rules,
            List<String> risk_notes
    ) {}

    public record RecoveryPlan(
            ExecutionEvent event,
            RecoveryStrategy llm_strategy,
            String failed_node,
            InterfaceBinding original_binding,
            List<String> affected_subgraph,
            List<String> frozen_tasks,
            List<String> changed_tasks,
            String replacement_robot,
            List<InterfaceBinding> recovery_bindings,
            int disturbance_cost,
            String explanation
    ) {}

    public record AgentStatus(String name, String status, String output, String implementation) {}

    public record LlmTrace(String agent, String provider, String model, long latency_ms, String response_id, Map<String, Object> usage) {}

    public record RunLogEntry(
            String run_id,
            int step,
            String stage,
            String direction,
            String title,
            Object payload,
            long elapsed_ms,
            String timestamp
    ) {}

    public record PipelineResponse(
            String mode,
            String model,
            List<AgentStatus> agents,
            List<LlmTrace> llm_traces,
            TaskIntent intent,
            TaskGraph task_graph,
            ValidationReport validation,
            ExecutionPlan execution_plan,
            RecoveryPlan recovery,
            List<RunLogEntry> run_logs,
            List<String> final_artifacts
    ) {}
}
