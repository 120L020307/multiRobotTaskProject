package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;

import java.util.*;
import java.util.stream.Collectors;

public final class GraphSupport {
    private static final Set<String> KNOWN_TASK_TYPES = Set.of(
            "inspect_area", "recheck_area", "transport_object", "navigate", "patrol_area",
            "enter_narrow_area", "search_person", "guard_area", "deliver_small_item",
            "map_area", "track_target", "relay_communication", "clear_obstacle", "tow_robot"
    );
    private static final Set<String> GENERIC_ANOMALY_AREAS = Set.of("异常点周边", "异常位置", "异常点", "异常区域");
    private static final List<String> RECOVERY_CLUES = List.of("低电量", "电量低", "电量不足", "阻塞", "失败", "超时", "low_battery", "blocked", "failed", "timeout", "recover");
    private static final List<String> PARALLEL_CLUES = List.of("同时", "并行", "与此同时", "同步");
    private static final List<String> CONDITION_CLUES = List.of("如果", "若", "发现", "条件", "condition");

    private GraphSupport() {}

    public static EvidenceSpan span(String rawText, Object evidence) {
        if (evidence instanceof Map<?, ?> map && map.get("start") instanceof Number startNum && map.get("end") instanceof Number endNum) {
            int start = startNum.intValue();
            int end = endNum.intValue();
            if (start >= 0 && end > start && end <= rawText.length()) return new EvidenceSpan(rawText.substring(start, end), start, end);
        }
        String text = evidenceText(evidence);
        int start = text.isBlank() ? -1 : rawText.indexOf(text);
        return new EvidenceSpan(text, start, start < 0 ? -1 : start + text.length());
    }

    @SuppressWarnings("unchecked")
    public static TaskGraph mapToGraph(Map<String, Object> data, String rawText) {
        List<Map<String, Object>> rawNodes = (List<Map<String, Object>>) data.getOrDefault("nodes", List.of());
        List<TaskNode> nodes = new ArrayList<>();
        for (int i = 0; i < rawNodes.size(); i++) {
            Map<String, Object> node = rawNodes.get(i);
            String originalTaskType = str(node.get("task_type"));
            String actorType = normalizeActorType(str(node.get("actor_type")));
            String taskType = normalizeTaskType(originalTaskType, actorType);
            Map<String, Object> params = normalizeParams(objectMap(node.get("params")), taskType, rawText);
            EvidenceSpan evidence = span(rawText, node.get("evidence"));
            if (evidence.start() < 0) evidence = inferNodeEvidence(rawText, taskType, actorType, originalTaskType, params);
            nodes.add(new TaskNode(
                    str(node.getOrDefault("id", "T" + (i + 1))),
                    taskType,
                    actorType,
                    params,
                    objectMap(node.get("output")),
                    str(node.getOrDefault("status", "pending")),
                    num(node.getOrDefault("confidence", 0.82)),
                    stringList(node.get("missing_fields")),
                    evidence
            ));
        }
        List<Map<String, Object>> rawEdges = (List<Map<String, Object>>) data.getOrDefault("edges", List.of());
        List<TaskEdge> edges = new ArrayList<>();
        for (int i = 0; i < rawEdges.size(); i++) {
            Map<String, Object> edge = rawEdges.get(i);
            EvidenceSpan evidence = span(rawText, edge.get("evidence"));
            if (evidence.start() < 0 && edge.get("condition") != null) evidence = span(rawText, edge.get("condition"));
            String edgeType = normalizeEdgeType(str(edge.get("type")), evidence, edge.get("condition"));
            edges.add(new TaskEdge(
                    str(edge.getOrDefault("id", "E" + (i + 1))),
                    str(edge.get("source")),
                    str(edge.get("target")),
                    edgeType,
                    edge.get("condition") == null ? null : str(edge.get("condition")),
                    evidence
            ));
        }
        List<Map<String, Object>> rawConstraints = (List<Map<String, Object>>) data.getOrDefault("constraints", List.of());
        List<TaskConstraint> constraints = rawConstraints.stream().map(c -> new TaskConstraint(
                str(c.get("type")), c.get("value"), c.get("target_actor") == null ? null : str(c.get("target_actor")), span(rawText, c.get("evidence"))
        )).toList();
        return contractNormalize(new TaskGraph(nodes, edges, constraints, rawText, null));
    }

    public static TaskGraph contractNormalize(TaskGraph graph) {
        List<TaskNode> normalizedNodes = graph.nodes().stream().map(node -> {
            String actorType = normalizeActorType(node.actor_type());
            String taskType = normalizeTaskType(node.task_type(), actorType);
            Map<String, Object> params = normalizeParams(new LinkedHashMap<>(node.params() == null ? Map.of() : node.params()), taskType, graph.raw_text());
            Set<String> missing = new LinkedHashSet<>(node.missing_fields() == null ? List.of() : node.missing_fields());
            return new TaskNode(node.id(), taskType, actorType, params, node.output(), node.status(), node.confidence(), new ArrayList<>(missing), node.evidence());
        }).collect(Collectors.toList());
        TaskGraph folded = foldSystemDetectionNodes(new TaskGraph(normalizedNodes, graph.edges(), graph.constraints(), graph.raw_text(), null));
        Map<String, String> incoming = new HashMap<>();
        for (TaskEdge edge : folded.edges()) {
            if (!incoming.containsKey(edge.target()) || "condition".equals(edge.type()) || "sequence".equals(edge.type())) incoming.put(edge.target(), edge.source());
        }
        List<TaskNode> nodes = folded.nodes().stream().map(node -> {
            String actorType = node.actor_type();
            String taskType = node.task_type();
            Map<String, Object> params = new LinkedHashMap<>(node.params() == null ? Map.of() : node.params());
            Set<String> missing = new LinkedHashSet<>(node.missing_fields() == null ? List.of() : node.missing_fields());
            if ("inspect_area".equals(taskType)) {
                params.putIfAbsent("area", inferArea(folded.raw_text(), params, "A区"));
                params.putIfAbsent("mode", "visual_thermal");
                missing.remove("area");
                missing.remove("mode");
            }
            if ("recheck_area".equals(taskType)) {
                String sourceNode = incoming.getOrDefault(node.id(), "anomaly_detection_output");
                params.putIfAbsent("source", sourceNode);
                if (!params.containsKey("area") || isGenericAnomalyArea(params.get("area"))) {
                    params.put("area", "${" + sourceNode + ".anomaly_location}");
                }
                missing.remove("area");
                missing.remove("source");
            }
            if ("transport_object".equals(taskType) || "deliver_small_item".equals(taskType)) {
                params.putIfAbsent("object", inferObject(folded.raw_text(), params, "物资"));
                params.putIfAbsent("target", params.getOrDefault("area", inferArea(folded.raw_text(), params, "异常点周边")));
                missing.remove("object");
                missing.remove("target");
            }
            return new TaskNode(node.id(), taskType, actorType, params, node.output(), node.status(), node.confidence(), new ArrayList<>(missing), node.evidence());
        }).collect(Collectors.toList());
        return withCoverage(new TaskGraph(nodes, folded.edges(), folded.constraints(), folded.raw_text(), null));
    }

    private static TaskGraph foldSystemDetectionNodes(TaskGraph graph) {
        Map<String, TaskNode> byId = graph.nodes().stream().collect(Collectors.toMap(TaskNode::id, node -> node, (a, b) -> a, LinkedHashMap::new));
        Set<String> foldedIds = graph.nodes().stream()
                .filter(GraphSupport::isSystemDetectionNode)
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
                    String edgeType = foldedEdgeType(systemNode);
                    String key = in.source() + "->" + out.target() + ":" + edgeType;
                    if (!edgeKeys.add(key)) continue;
                    edges.add(new TaskEdge(
                            "E_SYS_" + generated++,
                            in.source(),
                            out.target(),
                            edgeType,
                            foldedCondition(systemNode, out.condition(), in.condition()),
                            bestEvidence(systemNode.evidence(), out.evidence(), in.evidence())
                    ));
                }
            }
        }
        return new TaskGraph(nodes, edges, graph.constraints(), graph.raw_text(), null);
    }

    private static boolean isSystemDetectionNode(TaskNode node) {
        if (!"SYSTEM".equals(node.actor_type())) return false;
        String text = (str(node.task_type()) + " " + evidenceText(node.evidence()) + " " + str(node.params())).toLowerCase(Locale.ROOT);
        return text.contains("异常") || text.contains("电量") || text.contains("检测") || text.contains("判定")
                || text.contains("anomaly") || text.contains("battery") || text.contains("detect") || text.contains("check");
    }

    private static String foldedEdgeType(TaskNode systemNode) {
        String text = (str(systemNode.task_type()) + " " + evidenceText(systemNode.evidence()) + " " + str(systemNode.params())).toLowerCase(Locale.ROOT);
        if (text.contains("电量") || text.contains("battery") || text.contains("low_battery")) return "recovery";
        return "condition";
    }

    private static String foldedCondition(TaskNode systemNode, String primary, String fallback) {
        String text = (str(systemNode.task_type()) + " " + evidenceText(systemNode.evidence()) + " " + str(systemNode.params())).toLowerCase(Locale.ROOT);
        if (text.contains("电量") || text.contains("battery") || text.contains("low_battery")) return "low_battery";
        if (text.contains("异常") || text.contains("anomaly")) return "anomaly_detected == true";
        String condition = str(primary).isBlank() ? str(fallback) : str(primary);
        return condition.isBlank() ? "system_condition == true" : condition;
    }

    private static EvidenceSpan bestEvidence(EvidenceSpan... spans) {
        for (EvidenceSpan span : spans) if (span != null && span.start() >= 0) return span;
        return new EvidenceSpan("", -1, -1);
    }

    private static Map<String, Object> normalizeParams(Map<String, Object> input, String taskType, String rawText) {
        Map<String, Object> params = new LinkedHashMap<>();
        input.forEach((key, value) -> params.put(normalizeParamKey(key), value));
        if ("inspect_area".equals(taskType)) params.putIfAbsent("area", inferArea(rawText, params, null));
        if ("recheck_area".equals(taskType)) params.putIfAbsent("area", inferArea(rawText, params, "异常点周边"));
        if ("transport_object".equals(taskType) || "deliver_small_item".equals(taskType)) params.putIfAbsent("object", inferObject(rawText, params, null));
        return params;
    }

    private static String normalizeParamKey(String key) {
        String value = str(key).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (Set.of("area", "area_id", "target_area", "source_area", "location", "zone").contains(lower) || value.contains("区域") || value.contains("地点") || value.contains("位置")) return "area";
        if (Set.of("object", "object_id", "target_object", "item").contains(lower) || value.contains("对象") || value.contains("物体") || value.contains("工具") || value.contains("物品")) return "object";
        if (Set.of("source", "source_task", "from").contains(lower) || value.contains("来源")) return "source";
        if (Set.of("mode", "inspect_mode").contains(lower) || value.contains("模式")) return "mode";
        return value;
    }

    private static String inferArea(String rawText, Map<String, Object> params, String fallback) {
        for (Object value : params.values()) {
            String text = str(value);
            if (text.endsWith("区") || text.contains("异常点周边") || text.contains("工具间")) return text;
        }
        for (String candidate : List.of("异常点周边", "工具间", "A区", "B区")) if (rawText.contains(candidate)) return candidate;
        return fallback;
    }

    private static String inferObject(String rawText, Map<String, Object> params, String fallback) {
        for (Object value : params.values()) {
            String text = str(value);
            if (text.contains("箱") || text.contains("工具") || text.contains("物资")) return text;
        }
        for (String candidate : List.of("工具箱", "工具", "物资")) if (rawText.contains(candidate)) return candidate;
        return fallback;
    }

    private static String evidenceText(Object evidence) {
        if (evidence == null) return "";
        if (evidence instanceof EvidenceSpan span) return str(span.text()).trim();
        if (evidence instanceof Map<?, ?> map) {
            for (String key : List.of("text", "evidence_text", "span", "phrase", "raw_text", "content")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
            }
        }
        return String.valueOf(evidence).trim();
    }

    private static String normalizeActorType(String actorType) {
        String value = str(actorType).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.contains("无人机") || lower.contains("uav") || lower.contains("drone")) return "UAV";
        if (value.contains("无人车") || value.contains("地面") || lower.contains("ugv") || lower.contains("vehicle")) return "UGV";
        if (value.contains("无人狗") || value.contains("机器狗") || lower.contains("dog") || lower.contains("quadruped")) return "DOG";
        if (value.contains("系统") || lower.contains("system")) return "SYSTEM";
        return value;
    }

    private static String normalizeTaskType(String taskType, String actorType) {
        String value = str(taskType).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (KNOWN_TASK_TYPES.contains(lower)) return lower;
        if (lower.contains("recheck") || lower.contains("review") || value.contains("复查") || value.contains("复检") || value.contains("核查")) return "recheck_area";
        if ("DOG".equals(actorType) && (lower.contains("patrol") || value.contains("巡逻") || value.contains("值守"))) return "patrol_area";
        if ("DOG".equals(actorType) && (value.contains("搜索") || value.contains("搜寻") || value.contains("人员") || lower.contains("search"))) return "search_person";
        if ("DOG".equals(actorType) && (value.contains("狭窄") || value.contains("楼梯") || value.contains("管廊") || value.contains("地下通道") || lower.contains("narrow"))) return "enter_narrow_area";
        if (lower.contains("inspect") || lower.contains("patrol") || value.contains("巡检") || value.contains("巡视") || value.contains("检查")) return "inspect_area";
        if (lower.contains("pick") || lower.contains("grasp") || value.contains("抓取") || value.contains("拿取") || value.contains("拾取")) return "deliver_small_item";
        if (lower.contains("place") || value.contains("放置") || value.contains("放下")) return "deliver_small_item";
        if (lower.contains("transport") || value.contains("运输") || value.contains("搬运")) return "transport_object";
        if (value.contains("换车") || value.contains("替换") || value.contains("接替") || value.contains("继续复查")) return "recheck_area";
        if (value.contains("异常") && "UAV".equals(actorType)) return "inspect_area";
        return value;
    }

    private static String normalizeEdgeType(String type, EvidenceSpan evidence, Object condition) {
        String value = str(type).trim().toLowerCase(Locale.ROOT);
        String clue = (str(evidence == null ? null : evidence.text()) + " " + str(condition)).trim();
        String lowerClue = clue.toLowerCase(Locale.ROOT);
        if (containsAny(clue, lowerClue, RECOVERY_CLUES)) return "recovery";
        if (containsAny(clue, lowerClue, PARALLEL_CLUES)) return "parallel";
        if (containsAny(clue, lowerClue, CONDITION_CLUES) || value.contains("condition")) return "condition";
        if (Set.of("sequence", "parallel", "condition", "recovery").contains(value)) return value;
        return value.isBlank() ? "sequence" : value;
    }

    private static boolean isGenericAnomalyArea(Object value) {
        String text = str(value).trim();
        return text.isBlank() || GENERIC_ANOMALY_AREAS.contains(text);
    }

    private static boolean containsAny(String original, String lower, List<String> clues) {
        for (String clue : clues) {
            String target = clue.chars().anyMatch(ch -> ch > 127) ? original : lower;
            if (target.contains(clue)) return true;
        }
        return false;
    }

    private static EvidenceSpan inferNodeEvidence(String rawText, String taskType, String actorType, String originalTaskType, Map<String, Object> params) {
        List<String> candidates = new ArrayList<>();
        candidates.add(str(originalTaskType));
        if ("inspect_area".equals(taskType)) candidates.addAll(List.of("无人机先巡检A区", "巡检A区", "巡检"));
        if ("recheck_area".equals(taskType)) candidates.addAll(List.of("派无人车到异常点周边复查", "换另一台无人车继续复查", "异常点周边复查", "继续复查", "复查"));
        if ("deliver_small_item".equals(taskType)) candidates.addAll(List.of("急救包", "小件", "配送", "递送", "抓取工具箱", "工具箱"));
        if ("transport_object".equals(taskType)) candidates.addAll(List.of("运输", "搬运"));
        params.values().forEach(value -> candidates.add(str(value)));
        for (String candidate : candidates) {
            EvidenceSpan span = span(rawText, candidate);
            if (span.start() >= 0) return span;
        }
        return new EvidenceSpan("", -1, -1);
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
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return new ArrayList<>();
    }

    public static String str(Object value) { return value == null ? "" : String.valueOf(value); }
    public static double num(Object value) { return NumberSupport.toDouble(value, 0.82); }
}
