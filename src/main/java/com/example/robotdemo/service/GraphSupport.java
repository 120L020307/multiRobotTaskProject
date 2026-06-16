package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;

import java.util.*;

public final class GraphSupport {
    private GraphSupport() {}

    public static EvidenceSpan span(String rawText, Object evidence) {
        String source = rawText == null ? "" : rawText;
        if (evidence instanceof Map<?, ?> map && map.get("start") instanceof Number startNum && map.get("end") instanceof Number endNum) {
            int start = startNum.intValue();
            int end = endNum.intValue();
            if (start >= 0 && end > start && end <= source.length()) return new EvidenceSpan(source.substring(start, end), start, end);
        }
        String text = evidenceText(evidence);
        int start = text.isBlank() ? -1 : source.indexOf(text);
        return new EvidenceSpan(text, start, start < 0 ? -1 : start + text.length());
    }

    @SuppressWarnings("unchecked")
    public static TaskGraph mapToGraph(Map<String, Object> data, String rawText) {
        List<Map<String, Object>> rawNodes = (List<Map<String, Object>>) data.getOrDefault("nodes", List.of());
        List<TaskNode> nodes = new ArrayList<>();
        for (int i = 0; i < rawNodes.size(); i++) {
            Map<String, Object> node = rawNodes.get(i);
            nodes.add(new TaskNode(
                    str(node.getOrDefault("id", "T" + (i + 1))),
                    str(node.get("task_type")),
                    str(node.get("actor_type")),
                    objectMap(node.get("params")),
                    objectMap(node.get("output")),
                    str(node.getOrDefault("status", "pending")),
                    num(node.getOrDefault("confidence", 0.82)),
                    stringList(node.get("missing_fields")),
                    span(rawText, node.get("evidence"))
            ));
        }
        List<Map<String, Object>> rawEdges = (List<Map<String, Object>>) data.getOrDefault("edges", List.of());
        List<TaskEdge> edges = new ArrayList<>();
        for (int i = 0; i < rawEdges.size(); i++) {
            Map<String, Object> edge = rawEdges.get(i);
            EvidenceSpan evidence = span(rawText, edge.get("evidence"));
            if (evidence.start() < 0 && edge.get("condition") != null) evidence = span(rawText, edge.get("condition"));
            edges.add(new TaskEdge(
                    str(edge.getOrDefault("id", "E" + (i + 1))),
                    str(edge.get("source")),
                    str(edge.get("target")),
                    str(edge.getOrDefault("type", "sequence")),
                    edge.get("condition") == null ? null : str(edge.get("condition")),
                    evidence
            ));
        }
        List<Map<String, Object>> rawConstraints = (List<Map<String, Object>>) data.getOrDefault("constraints", List.of());
        List<TaskConstraint> constraints = rawConstraints.stream().map(c -> new TaskConstraint(
                str(c.get("type")), c.get("value"), c.get("target_actor") == null ? null : str(c.get("target_actor")), span(rawText, c.get("evidence"))
        )).toList();
        return withCoverage(new TaskGraph(nodes, edges, constraints, rawText, null));
    }

    public static TaskGraph withCoverage(TaskGraph graph) {
        int nodesWith = (int) graph.nodes().stream().filter(n -> n.evidence() != null && n.evidence().start() >= 0).count();
        int edgesWith = (int) graph.edges().stream().filter(e -> e.evidence() != null && e.evidence().start() >= 0).count();
        int total = Math.max(1, graph.nodes().size() + graph.edges().size());
        double ratio = Math.round(((nodesWith + edgesWith) * 1.0 / total) * 100.0) / 100.0;
        return new TaskGraph(graph.nodes(), graph.edges(), graph.constraints(), graph.raw_text(), new EvidenceCoverage(nodesWith, graph.nodes().size(), edgesWith, graph.edges().size(), ratio));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return new LinkedHashMap<>();
    }

    public static List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return new ArrayList<>();
    }

    public static String evidenceText(Object evidence) {
        if (evidence instanceof EvidenceSpan span) return span.text();
        if (evidence instanceof Map<?, ?> map) {
            Object text = map.containsKey("text") ? map.get("text") : map.get("quote");
            return str(text);
        }
        return str(evidence);
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static double num(Object value) {
        return NumberSupport.toDouble(value, 0.82);
    }
}
