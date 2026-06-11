package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;

import java.util.*;

public class BlackboardWorkspace {
    private final Map<String, Object> slots = new LinkedHashMap<>();
    private final List<BlackboardEntry> entries = new ArrayList<>();
    private int version = 0;

    public BlackboardWorkspace(String rawText, ExecutionEvent event, List<RobotContract> contracts) {
        write("System", "RawTaskText", rawText, "用户输入的自然语言任务");
        write("System", "ExecutionEvent", event, "外部异常事件；为空时后续 Agent 可生成演示事件");
        write("System", "CapabilityContracts", List.copyOf(contracts), "可用机器人能力契约库");
    }

    public void write(String agent, String slot, Object value, String note) {
        slots.put(slot, value);
        entries.add(new BlackboardEntry(++version, agent, slot, note, summarize(value)));
    }

    public String rawText() { return read("RawTaskText", String.class); }
    public ExecutionEvent event() { return read("ExecutionEvent", ExecutionEvent.class); }
    public List<RobotContract> contracts() { return list("CapabilityContracts"); }
    public TaskIntent intent() { return read("TaskIntent", TaskIntent.class); }
    public TaskGraph taskGraph() { return read("TaskGraph", TaskGraph.class); }
    public ValidationReport validation() { return read("ValidationReport", ValidationReport.class); }
    public ExecutionPlan executionPlan() { return read("ExecutionPlan", ExecutionPlan.class); }
    public GeneratedRos2Code generatedCode() { return read("GeneratedRos2Code", GeneratedRos2Code.class); }
    public RecoveryPlan recoveryPlan() { return read("RecoveryPlan", RecoveryPlan.class); }

    public List<BlackboardEntry> entries() { return List.copyOf(entries); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("slots", slots.keySet());
        out.put("writes", entries);
        return out;
    }

    private <T> T read(String slot, Class<T> type) {
        Object value = slots.get(slot);
        return value == null ? null : type.cast(value);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> list(String slot) {
        Object value = slots.get(slot);
        return value == null ? List.of() : (List<T>) value;
    }

    private Map<String, Object> summarize(Object value) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (value == null) {
            summary.put("type", "null");
            return summary;
        }
        summary.put("type", value.getClass().getSimpleName());
        if (value instanceof String text) {
            summary.put("length", text.length());
            summary.put("preview", text.length() > 80 ? text.substring(0, 80) + "..." : text);
        } else if (value instanceof Collection<?> collection) {
            summary.put("size", collection.size());
        } else if (value instanceof TaskGraph graph) {
            summary.put("nodes", graph.nodes().size());
            summary.put("edges", graph.edges().size());
            summary.put("evidence_coverage", graph.evidence_coverage());
        } else if (value instanceof ValidationReport report) {
            summary.put("overall_valid", report.overall_valid());
            summary.put("errors", report.errors() == null ? 0 : report.errors().size());
            summary.put("warnings", report.warnings() == null ? 0 : report.warnings().size());
        } else if (value instanceof ExecutionPlan plan) {
            summary.put("bindings", plan.bindings().size());
            summary.put("unmapped", plan.unmapped().size());
            summary.put("schedule", plan.schedule().size());
        } else if (value instanceof GeneratedRos2Code code) {
            summary.put("language", code.language());
            summary.put("entrypoint", code.entrypoint());
            summary.put("files", code.files());
        } else if (value instanceof RecoveryPlan plan) {
            summary.put("failed_node", plan.failed_node());
            summary.put("replacement_robot", plan.replacement_robot());
            summary.put("disturbance_cost", plan.disturbance_cost());
        }
        return summary;
    }

    public record BlackboardEntry(int version, String agent, String slot, String note, Map<String, Object> summary) {}
}
