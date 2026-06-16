package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NormalizationRuleService {
    private static final String RULE_RESOURCE = "data/normalization_rules.json";
    private final NormalizationRules rules;

    public NormalizationRuleService(ObjectMapper mapper) {
        this.rules = load(mapper);
    }

    public NormalizationRules rules() {
        return rules;
    }

    public String normalizeActorType(String actorType) {
        String raw = GraphSupport.str(actorType).trim();
        if (raw.isBlank()) return raw;
        Map<String, String> aliases = rules.actor_aliases() == null ? Map.of() : rules.actor_aliases();
        String direct = aliases.get(raw);
        if (direct != null) return direct;
        String upper = raw.toUpperCase(Locale.ROOT);
        direct = aliases.get(upper);
        if (direct != null) return direct;
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String alias = entry.getKey();
            if (!alias.isBlank() && raw.contains(alias)) return entry.getValue();
        }
        return upper;
    }

    public String normalizeCapability(TaskNode node, List<RobotContract> contracts) {
        if (node == null || "SYSTEM".equals(node.actor_type())) return node == null ? "" : node.task_type();
        String actorType = normalizeActorType(node.actor_type());
        String query = queryText(node);
        CandidateScore best = null;
        for (RobotContract robot : safeRobots(contracts)) {
            if (!Objects.equals(normalizeActorType(robot.robot_type()), actorType)) continue;
            for (Contract contract : safeContracts(robot)) {
                double score = score(contract, query, node.task_type());
                if (best == null || score > best.score()) best = new CandidateScore(contract.capability_name(), score);
            }
        }
        return best != null && best.score() >= 1.0 ? best.capability() : GraphSupport.str(node.task_type());
    }

    public Optional<Contract> contractFor(String actorType, String capability, List<RobotContract> contracts) {
        String normalizedActor = normalizeActorType(actorType);
        for (RobotContract robot : safeRobots(contracts)) {
            if (!Objects.equals(normalizeActorType(robot.robot_type()), normalizedActor)) continue;
            for (Contract contract : safeContracts(robot)) {
                if (Objects.equals(contract.capability_name(), capability)) return Optional.of(contract);
            }
        }
        return Optional.empty();
    }

    public Map<String, Object> normalizeParamKeys(Map<String, Object> params, Contract contract) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<String>> aliases = mergedParamAliases(contract);
        for (Map.Entry<String, Object> entry : safeMap(params).entrySet()) {
            String canonical = canonicalParam(entry.getKey(), aliases);
            out.putIfAbsent(canonical, entry.getValue());
        }
        return out;
    }

    public Map<String, Object> applyDefaultsAndDerived(Map<String, Object> params, TaskNode node, Contract contract, TaskGraph graph) {
        Map<String, Object> out = new LinkedHashMap<>(safeMap(params));
        if (contract != null && contract.default_params() != null) {
            contract.default_params().forEach(out::putIfAbsent);
        }
        for (String required : requiredParams(contract)) {
            if (out.containsKey(required)) continue;
            Object inferred = inferRequiredParam(required, out, node, graph);
            if (inferred != null) out.put(required, inferred);
        }
        if (contract != null && contract.derived_params() != null) {
            contract.derived_params().forEach((key, template) -> {
                if (!out.containsKey(key)) out.put(key, resolveTemplate(template, node, graph));
            });
        }
        return out;
    }

    public List<String> remainingMissingFields(Collection<String> currentMissing, Map<String, Object> params, Contract contract) {
        Set<String> missing = new LinkedHashSet<>(currentMissing == null ? List.of() : currentMissing);
        for (String required : requiredParams(contract)) {
            if (params.containsKey(required) && !GraphSupport.str(params.get(required)).isBlank()) missing.remove(required);
            else missing.add(required);
        }
        return new ArrayList<>(missing);
    }

    public String normalizeEdgeType(String edgeType, EvidenceSpan evidence, Object condition) {
        String value = GraphSupport.str(edgeType).trim().toLowerCase(Locale.ROOT);
        String text = (GraphSupport.str(evidence == null ? null : evidence.text()) + " " + GraphSupport.str(condition)).trim();
        Optional<EventRule> rule = matchEventRule(text);
        if (rule.isPresent()) return rule.get().edge_type();
        if (textContainsAny(text, List.of("同时", "并行", "与此同时", "同步", "parallel"))) return "parallel";
        if (textContainsAny(text, List.of("如果", "若", "条件", "condition")) || value.contains("condition")) return "condition";
        if (Set.of("sequence", "parallel", "condition", "recovery").contains(value)) return value;
        return value.isBlank() ? "sequence" : value;
    }

    public boolean isSystemDetectionNode(TaskNode node) {
        if (!"SYSTEM".equals(node.actor_type())) return false;
        return matchEventRule(nodeText(node)).isPresent() || textContainsAny(nodeText(node), List.of("检测", "判定", "detect", "check"));
    }

    public String foldedEdgeType(TaskNode systemNode) {
        return matchEventRule(nodeText(systemNode)).map(EventRule::edge_type).orElse("condition");
    }

    public String foldedCondition(TaskNode systemNode, String primary, String fallback) {
        Optional<EventRule> rule = matchEventRule(nodeText(systemNode));
        if (rule.isPresent()) return rule.get().condition();
        String condition = GraphSupport.str(primary).isBlank() ? GraphSupport.str(fallback) : GraphSupport.str(primary);
        return condition.isBlank() ? "system_condition == true" : condition;
    }

    public boolean isGenericReference(Object value) {
        String text = GraphSupport.str(value).trim();
        return text.isBlank() || safeList(rules.generic_reference_values()).contains(text);
    }

    public EvidenceSpan inferEvidence(String rawText, TaskNode node, Contract contract) {
        List<String> candidates = new ArrayList<>();
        candidates.add(GraphSupport.str(node.task_type()));
        if (contract != null) {
            candidates.add(contract.capability_name());
            candidates.addAll(safeList(contract.aliases()));
            candidates.add(contract.description());
        }
        if (node.params() != null) node.params().values().forEach(value -> candidates.add(GraphSupport.str(value)));
        return candidates.stream()
                .filter(text -> !text.isBlank() && !text.startsWith("${"))
                .distinct()
                .map(text -> GraphSupport.span(rawText, text))
                .filter(span -> span.start() >= 0)
                .findFirst()
                .orElse(new EvidenceSpan("", -1, -1));
    }

    public Optional<EventRule> matchEventRule(String text) {
        for (EventRule rule : safeList(rules.event_rules())) {
            if (textContainsAny(text, rule.keywords())) return Optional.of(rule);
        }
        return Optional.empty();
    }

    public Map<String, Double> schedulerWeights() {
        return rules.scheduler_weights() == null ? Map.of() : rules.scheduler_weights();
    }

    private Object inferRequiredParam(String required, Map<String, Object> params, TaskNode node, TaskGraph graph) {
        if ("source".equals(required)) return incomingSource(node.id(), graph).orElse(null);
        if ("area".equals(required)) {
            Object current = params.get("area");
            if (!isGenericReference(current)) return current;
            Optional<String> source = incomingSource(node.id(), graph);
            if (source.isPresent()) return "${" + source.get() + ".anomaly_location}";
            return firstAreaMention(graph.raw_text()).orElse(null);
        }
        if ("target".equals(required)) return params.getOrDefault("area", null);
        if ("object".equals(required)) return firstObjectMention(graph.raw_text()).orElse(null);
        return null;
    }

    private Optional<String> incomingSource(String nodeId, TaskGraph graph) {
        if (graph == null || graph.edges() == null) return Optional.empty();
        return graph.edges().stream()
                .filter(edge -> Objects.equals(edge.target(), nodeId))
                .filter(edge -> !"parallel".equals(edge.type()))
                .map(TaskEdge::source)
                .findFirst();
    }

    private Object resolveTemplate(String template, TaskNode node, TaskGraph graph) {
        if (template == null) return null;
        if (template.contains("${incoming.source}")) {
            return incomingSource(node.id(), graph).map(source -> template.replace("${incoming.source}", source)).orElse(template);
        }
        return template;
    }

    private Optional<String> firstAreaMention(String rawText) {
        String text = GraphSupport.str(rawText);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[A-ZＡ-Ｚ]区|[\\u4e00-\\u9fa5]{1,8}(?:外场|内场|主路|屋顶|门岗|仓库|通道|管廊|楼梯间|维修点|入口|出口)").matcher(text);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private Optional<String> firstObjectMention(String rawText) {
        String text = GraphSupport.str(rawText);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{1,8}(?:包|箱|设备|工具|物资|报告|检测仪)").matcher(text);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private List<String> requiredParams(Contract contract) {
        if (contract == null || contract.input_mapping() == null) return List.of();
        return new ArrayList<>(contract.input_mapping().keySet());
    }

    private String canonicalParam(String key, Map<String, List<String>> aliases) {
        String normalized = GraphSupport.str(key).trim();
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            if (entry.getKey().equals(normalized)) return entry.getKey();
            for (String alias : safeList(entry.getValue())) {
                if (alias.equalsIgnoreCase(normalized)) return entry.getKey();
            }
        }
        return normalized;
    }

    private Map<String, List<String>> mergedParamAliases(Contract contract) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (rules.parameter_aliases() != null) out.putAll(rules.parameter_aliases());
        if (contract != null && contract.param_aliases() != null) contract.param_aliases().forEach((key, values) -> {
            List<String> merged = new ArrayList<>(out.getOrDefault(key, List.of()));
            merged.addAll(safeList(values));
            out.put(key, merged);
        });
        return out;
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

    private double score(Contract contract, String query, String taskType) {
        String capability = norm(contract.capability_name());
        String task = norm(taskType);
        if (!capability.isBlank() && capability.equals(task)) return 100;
        double score = 0;
        if (!capability.isBlank() && query.contains(capability)) score = Math.max(score, 8);
        for (String alias : safeList(contract.aliases())) {
            String normalizedAlias = norm(alias);
            if (normalizedAlias.isBlank()) continue;
            if (normalizedAlias.equals(task)) score = Math.max(score, 12);
            if (query.contains(normalizedAlias)) score = Math.max(score, Math.min(10, Math.max(2, normalizedAlias.length() / 2.0)));
            score = Math.max(score, tokenOverlap(query, normalizedAlias));
        }
        String description = norm(contract.description());
        if (!description.isBlank()) score = Math.max(score, tokenOverlap(query, description));
        return score;
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

    private boolean textContainsAny(String text, List<String> keywords) {
        String original = GraphSupport.str(text);
        String lower = original.toLowerCase(Locale.ROOT);
        for (String keyword : safeList(keywords)) {
            String key = GraphSupport.str(keyword);
            String target = key.chars().anyMatch(ch -> ch > 127) ? original : lower;
            if (target.contains(key.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String nodeText(TaskNode node) {
        return GraphSupport.str(node.task_type()) + " " + GraphSupport.str(node.evidence() == null ? null : node.evidence().text()) + " " + GraphSupport.str(node.params());
    }

    private Map<String, Object> safeMap(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }

    private List<RobotContract> safeRobots(List<RobotContract> robots) {
        return robots == null ? List.of() : robots;
    }

    private List<Contract> safeContracts(RobotContract robot) {
        return robot == null || robot.contracts() == null ? List.of() : robot.contracts();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String norm(String value) {
        return GraphSupport.str(value).trim().toLowerCase(Locale.ROOT);
    }

    private NormalizationRules load(ObjectMapper mapper) {
        try (InputStream input = new ClassPathResource(RULE_RESOURCE).getInputStream()) {
            return mapper.readValue(input, new TypeReference<>() {});
        } catch (Exception e) {
            return new NormalizationRules(Map.of(), List.of(), Map.of(), List.of(), Map.of("battery", 0.6, "idle", 0.4));
        }
    }

    private record CandidateScore(String capability, double score) {}
}
