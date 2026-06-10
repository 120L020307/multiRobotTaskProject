package com.example.robotdemo.service;

import com.example.robotdemo.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class Ros2CodegenService {
    public GeneratedRos2Code generate(TaskGraph graph, ExecutionPlan plan) {
        Map<String, TaskNode> nodes = graph.nodes().stream().collect(Collectors.toMap(TaskNode::id, n -> n, (a, b) -> a, LinkedHashMap::new));
        StringBuilder code = new StringBuilder();
        code.append("#!/usr/bin/env python3\n");
        code.append("\"\"\"Auto-generated ROS 2 execution skeleton from TaskGraph + InterfaceBinding.\n\n");
        code.append("This executor preserves graph semantics: sequence/parallel edges schedule tasks,\n");
        code.append("condition edges are guarded by runtime predicates, and recovery edges are\n");
        code.append("activated by execution events such as low_battery, timeout, or blocked.\n");
        code.append("\"\"\"\n\n");
        code.append("import json\n");
        code.append("import rclpy\n");
        code.append("from rclpy.node import Node\n\n\n");
        code.append("class GeneratedTaskExecutor(Node):\n");
        code.append("    def __init__(self):\n");
        code.append("        super().__init__('generated_task_executor')\n");
        code.append("        self.plan = ");
        code.append(toPythonPlan(plan));
        code.append("\n");
        code.append("        self.nodes = ");
        code.append(toPythonNodes(graph));
        code.append("\n");
        code.append("        self.edges = ");
        code.append(toPythonEdges(graph));
        code.append("\n");
        code.append("        self.bindings = ");
        code.append(toPythonBindings(plan));
        code.append("\n");
        code.append("        self.results = {}\n");
        code.append("        self.events = []\n");
        code.append("        self.completed = set()\n");
        code.append("        self.skipped = set()\n\n");
        appendExecute(code);
        appendRuntimeHelpers(code);
        for (RosInterface rosInterface : uniqueInterfaces(plan)) appendInterfaceMethod(code, rosInterface);
        appendMain(code);

        List<String> assumptions = List.of(
                "生成代码保留 TaskGraph 的 sequence/parallel/condition/recovery 语义；并行任务在骨架中按就绪队列串行执行，真实部署可替换为异步调度。",
                "机器人动作结果统一写入 self.results[node_id]；条件边从上游结果、系统检测结果和事件上下文中求值。",
                "异常检测、机器人动态重选和具体 Action/Service/Topic 类型以可替换钩子函数形式生成，便于接入真实 ROS 2 包。",
                "接口参数来自能力契约 input_mapping；形如 ${T1.anomaly_location} 的参数会在运行时从上下文解析。"
        );
        List<String> safetyNotes = List.of(
                "真实机器人执行前必须加入急停、权限、仿真环境和参数边界检查。",
                "条件判断、异常检测和恢复策略的占位实现必须替换为经过验证的算法或服务。",
                "未绑定 InterfaceBinding 的任务会被跳过并输出告警。"
        );
        return new GeneratedRos2Code("python", "generated_robot_task_executor", "generated_task_executor.py", List.of("generated_task_executor.py"), code.toString(), assumptions, safetyNotes);
    }

    private void appendExecute(StringBuilder code) {
        code.append("    def execute(self):\n");
        code.append("        self.get_logger().info('Starting generated graph executor')\n");
        code.append("        pending = set(self.nodes.keys())\n");
        code.append("        while pending:\n");
        code.append("            ready = [node_id for node_id in sorted(pending, key=self._node_order) if self._is_ready(node_id)]\n");
        code.append("            if not ready:\n");
        code.append("                self.get_logger().warn(f'No ready nodes; pending={sorted(pending)} skipped={sorted(self.skipped)}')\n");
        code.append("                break\n");
        code.append("            for node_id in ready:\n");
        code.append("                pending.remove(node_id)\n");
        code.append("                if self._is_blocked_by_condition(node_id):\n");
        code.append("                    self.skipped.add(node_id)\n");
        code.append("                    self.get_logger().info(f'Skip {node_id}: condition not satisfied')\n");
        code.append("                    continue\n");
        code.append("                if self._is_recovery_node(node_id) and not self._has_recovery_event(node_id):\n");
        code.append("                    self.skipped.add(node_id)\n");
        code.append("                    self.get_logger().info(f'Skip {node_id}: recovery event not active')\n");
        code.append("                    continue\n");
        code.append("                self._execute_node(node_id)\n");
        code.append("        self.get_logger().info('Generated graph executor finished')\n\n");
    }

    private void appendRuntimeHelpers(StringBuilder code) {
        code.append("    def _node_order(self, node_id):\n");
        code.append("        for row in self.plan:\n");
        code.append("            if row.get('node_id') == node_id:\n");
        code.append("                return row.get('order', 9999)\n");
        code.append("        return 9999\n\n");
        code.append("    def _incoming_edges(self, node_id, edge_type=None):\n");
        code.append("        return [edge for edge in self.edges if edge.get('target') == node_id and (edge_type is None or edge.get('type') == edge_type)]\n\n");
        code.append("    def _is_ready(self, node_id):\n");
        code.append("        for edge in self._incoming_edges(node_id):\n");
        code.append("            if edge.get('type') == 'parallel':\n");
        code.append("                continue\n");
        code.append("            source = edge.get('source')\n");
        code.append("            if source not in self.completed and source not in self.skipped:\n");
        code.append("                return False\n");
        code.append("        return True\n\n");
        code.append("    def _is_blocked_by_condition(self, node_id):\n");
        code.append("        for edge in self._incoming_edges(node_id, 'condition'):\n");
        code.append("            if edge.get('source') in self.skipped:\n");
        code.append("                return True\n");
        code.append("            if not self.evaluate_condition(edge.get('condition'), edge.get('source'), node_id):\n");
        code.append("                return True\n");
        code.append("        return False\n\n");
        code.append("    def _is_recovery_node(self, node_id):\n");
        code.append("        return bool(self._incoming_edges(node_id, 'recovery'))\n\n");
        code.append("    def _has_recovery_event(self, node_id):\n");
        code.append("        recovery_edges = self._incoming_edges(node_id, 'recovery')\n");
        code.append("        if not recovery_edges:\n");
        code.append("            return True\n");
        code.append("        return any(self.evaluate_condition(edge.get('condition'), edge.get('source'), node_id) for edge in recovery_edges)\n\n");
        code.append("    def _execute_node(self, node_id):\n");
        code.append("        binding = self.bindings.get(node_id)\n");
        code.append("        node = self.nodes.get(node_id, {})\n");
        code.append("        if not binding:\n");
        code.append("            self.get_logger().warn(f'Node {node_id} has no InterfaceBinding; skipped')\n");
        code.append("            self.skipped.add(node_id)\n");
        code.append("            return\n");
        code.append("        params = self.resolve_params(binding.get('params', {}))\n");
        code.append("        interface_name = binding.get('interface_name')\n");
        code.append("        self.get_logger().info(f'Execute {node_id} / {node.get(\"task_type\")} on {binding.get(\"robot_id\")}')\n");
        code.append("        result = self.call_ros_interface(binding, interface_name, params)\n");
        code.append("        result = self.postprocess_result(node_id, node, binding, result)\n");
        code.append("        self.results[node_id] = result\n");
        code.append("        self.completed.add(node_id)\n");
        code.append("        event = self.detect_execution_event(node_id, node, binding, result)\n");
        code.append("        if event:\n");
        code.append("            self.events.append(event)\n");
        code.append("            self.get_logger().warn(f'Runtime event: {json.dumps(event, ensure_ascii=False)}')\n\n");
        code.append("    def call_ros_interface(self, binding, interface_name, params):\n");
        code.append("        method = getattr(self, binding.get('method'), None)\n");
        code.append("        if method is None:\n");
        code.append("            self.get_logger().warn(f'Missing generated method for {interface_name}; using generic call')\n");
        code.append("            return self.generic_ros_call(interface_name, params)\n");
        code.append("        return method(interface_name, params)\n\n");
        code.append("    def generic_ros_call(self, interface_name, params):\n");
        code.append("        self.get_logger().info(f'[ROS2] {interface_name} <= {json.dumps(params, ensure_ascii=False)}')\n");
        code.append("        return {'status': 'ok', 'interface_name': interface_name, 'params': params}\n\n");
        code.append("    def resolve_params(self, params):\n");
        code.append("        return {key: self.resolve_value(value) for key, value in params.items()}\n\n");
        code.append("    def resolve_value(self, value):\n");
        code.append("        if isinstance(value, str) and value.startswith('${') and value.endswith('}'):\n");
        code.append("            path = value[2:-1].split('.')\n");
        code.append("            current = self.results.get(path[0], {})\n");
        code.append("            for part in path[1:]:\n");
        code.append("                if isinstance(current, dict):\n");
        code.append("                    current = current.get(part)\n");
        code.append("                else:\n");
        code.append("                    return value\n");
        code.append("            return current if current is not None else value\n");
        code.append("        return value\n\n");
        code.append("    def postprocess_result(self, node_id, node, binding, result):\n");
        code.append("        result = dict(result or {})\n");
        code.append("        task_type = node.get('task_type', '')\n");
        code.append("        if task_type in ('inspect_area', 'map_area', 'patrol_area'):\n");
        code.append("            result.setdefault('image_uri', f'file:///tmp/{node_id}_capture.jpg')\n");
        code.append("            result.setdefault('image_topic', f'/{binding.get(\"robot_id\", \"robot\").lower()}/camera/image_raw')\n");
        code.append("            result.setdefault('capture_pose', result.get('pose', 'unknown_pose'))\n");
        code.append("            result.update(self.detect_anomaly(node_id, node, result))\n");
        code.append("        if task_type in ('recheck_area', 'enter_narrow_area', 'search_person', 'guard_area'):\n");
        code.append("            params = result.get('params', {})\n");
        code.append("            result.setdefault('anomaly_location', params.get('goal.target_area') or params.get('goal.area_id') or node.get('params', {}).get('area'))\n");
        code.append("            result.setdefault('source_task', params.get('goal.source_task') or node.get('params', {}).get('source'))\n");
        code.append("            result.setdefault('report', f'{task_type} finished')\n");
        code.append("        return result\n\n");
        code.append("    def detect_anomaly(self, node_id, node, sensor_result):\n");
        code.append("        # TODO: replace with a real detector service/model, e.g. image topic -> anomaly event.\n");
        code.append("        raw_params = node.get('params', {})\n");
        code.append("        return {\n");
        code.append("            'anomaly_detected': sensor_result.get('anomaly_detected', True),\n");
        code.append("            'anomaly_location': sensor_result.get('anomaly_location') or raw_params.get('area') or '异常点周边',\n");
        code.append("            'anomaly_confidence': sensor_result.get('anomaly_confidence', 0.85),\n");
        code.append("            'source_task': node_id,\n");
        code.append("        }\n\n");
        code.append("    def evaluate_condition(self, condition, source_id, target_id):\n");
        code.append("        text = str(condition or '').lower()\n");
        code.append("        source_result = self.results.get(source_id, {})\n");
        code.append("        if 'anomaly' in text or '异常' in text or '发现' in text:\n");
        code.append("            return bool(source_result.get('anomaly_detected'))\n");
        code.append("        if 'low_battery' in text or 'battery' in text or '电量' in text:\n");
        code.append("            return any(event.get('event_type') == 'low_battery' and event.get('source_node') == source_id for event in self.events)\n");
        code.append("        if 'blocked' in text or '阻塞' in text:\n");
        code.append("            return any(event.get('event_type') == 'blocked' and event.get('source_node') == source_id for event in self.events)\n");
        code.append("        if text in ('', 'true', 'condition', 'sequence'):\n");
        code.append("            return True\n");
        code.append("        self.get_logger().warn(f'Unknown condition {condition!r}; default to True for skeleton execution')\n");
        code.append("        return True\n\n");
        code.append("    def detect_execution_event(self, node_id, node, binding, result):\n");
        code.append("        # TODO: replace with robot diagnostics/subscriptions.\n");
        code.append("        if result.get('event_type'):\n");
        code.append("            event = dict(result)\n");
        code.append("            event.setdefault('source_node', node_id)\n");
        code.append("            event.setdefault('robot_id', binding.get('robot_id'))\n");
        code.append("            return event\n");
        code.append("        return None\n\n");
    }

    private void appendInterfaceMethod(StringBuilder code, RosInterface rosInterface) {
        code.append("    def ").append(methodName(rosInterface)).append("(self, interface_name, params):\n");
        String type = rosInterface.type() == null ? "unknown" : rosInterface.type().toLowerCase(Locale.ROOT);
        if (type.contains("action")) {
            code.append("        # TODO: replace placeholder with the concrete Action type and ActionClient call.\n");
            code.append("        self.get_logger().info(f'[ACTION] {interface_name} <= {json.dumps(params, ensure_ascii=False)}')\n");
        } else if (type.contains("service")) {
            code.append("        # TODO: replace placeholder with the concrete Service type and async client call.\n");
            code.append("        self.get_logger().info(f'[SERVICE] {interface_name} <= {json.dumps(params, ensure_ascii=False)}')\n");
        } else if (type.contains("topic")) {
            code.append("        # TODO: replace placeholder with the concrete message type and publisher/subscriber.\n");
            code.append("        self.get_logger().info(f'[TOPIC] {interface_name} <= {json.dumps(params, ensure_ascii=False)}')\n");
        } else {
            code.append("        self.get_logger().info(f'[ROS2] {interface_name} <= {json.dumps(params, ensure_ascii=False)}')\n");
        }
        code.append("        return {'status': 'ok', 'interface_name': interface_name, 'params': params}\n\n");
    }

    private void appendMain(StringBuilder code) {
        code.append("\ndef main():\n");
        code.append("    rclpy.init()\n");
        code.append("    node = GeneratedTaskExecutor()\n");
        code.append("    try:\n");
        code.append("        node.execute()\n");
        code.append("    finally:\n");
        code.append("        node.destroy_node()\n");
        code.append("        rclpy.shutdown()\n\n\n");
        code.append("if __name__ == '__main__':\n");
        code.append("    main()\n");
    }

    private List<RosInterface> uniqueInterfaces(ExecutionPlan plan) {
        Map<String, RosInterface> out = new LinkedHashMap<>();
        for (InterfaceBinding binding : plan.bindings()) {
            if (binding.ros_interface() == null) continue;
            out.putIfAbsent(methodName(binding.ros_interface()), binding.ros_interface());
        }
        return new ArrayList<>(out.values());
    }

    private String toPythonPlan(ExecutionPlan plan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScheduleStep step : plan.schedule()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", step.order());
            row.put("node_id", step.node_id());
            if (step.binding() != null) {
                row.put("robot_id", step.binding().robot_id());
                row.put("robot_type", step.binding().robot_type());
                row.put("interface_type", step.binding().ros_interface().type());
                row.put("interface_name", step.binding().ros_interface().name());
                row.put("params", step.binding().interface_params());
                row.put("method", methodName(step.binding().ros_interface()));
            }
            rows.add(row);
        }
        return toPythonLiteral(rows);
    }

    private String toPythonNodes(TaskGraph graph) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (TaskNode node : graph.nodes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", node.id());
            row.put("task_type", node.task_type());
            row.put("actor_type", node.actor_type());
            row.put("params", node.params());
            out.put(node.id(), row);
        }
        return toPythonLiteral(out);
    }

    private String toPythonEdges(TaskGraph graph) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskEdge edge : graph.edges()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", edge.id());
            row.put("source", edge.source());
            row.put("target", edge.target());
            row.put("type", edge.type());
            row.put("condition", edge.condition());
            out.add(row);
        }
        return toPythonLiteral(out);
    }

    private String toPythonBindings(ExecutionPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (InterfaceBinding binding : plan.bindings()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node_id", binding.node_id());
            row.put("task_type", binding.task_type());
            row.put("robot_id", binding.robot_id());
            row.put("robot_type", binding.robot_type());
            row.put("interface_type", binding.ros_interface().type());
            row.put("interface_name", binding.ros_interface().name());
            row.put("params", binding.interface_params());
            row.put("method", methodName(binding.ros_interface()));
            out.put(binding.node_id(), row);
        }
        return toPythonLiteral(out);
    }

    private String methodName(RosInterface rosInterface) {
        String type = rosInterface == null || rosInterface.type() == null ? "ros2" : rosInterface.type().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        String name = rosInterface == null || rosInterface.name() == null ? "interface" : rosInterface.name().replaceAll("^/+", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("_+$", "").toLowerCase(Locale.ROOT);
        if (name.isBlank()) name = "interface";
        return "call_" + type + "_" + name;
    }

    private String toPythonLiteral(Object value) {
        if (value == null) return "None";
        if (value instanceof String s) return pyString(s);
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> pyString(String.valueOf(e.getKey())) + ": " + toPythonLiteral(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::toPythonLiteral).collect(Collectors.joining(", ", "[", "]"));
        }
        return pyString(String.valueOf(value));
    }

    private String pyString(String value) {
        return "'" + escapePy(value) + "'";
    }

    private String escapePy(String value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }
}
