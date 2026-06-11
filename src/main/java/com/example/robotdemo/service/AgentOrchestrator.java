package com.example.robotdemo.service;

import com.example.robotdemo.llm.DeepSeekClient;
import com.example.robotdemo.llm.DeepSeekClient.AgentResult;
import com.example.robotdemo.model.Models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final DeepSeekClient llm;
    private final DataLoader dataLoader;
    private final ValidationService validationService;
    private final ContractMatchingService contractMatchingService;
    private final Ros2CodegenService ros2CodegenService;
    private final RecoveryService recoveryService;
    private final CapabilityNormalizer capabilityNormalizer;
    private final ObjectMapper mapper;

    public AgentOrchestrator(DeepSeekClient llm, DataLoader dataLoader, ValidationService validationService, ContractMatchingService contractMatchingService, Ros2CodegenService ros2CodegenService, RecoveryService recoveryService, CapabilityNormalizer capabilityNormalizer, ObjectMapper mapper) {
        this.llm = llm;
        this.dataLoader = dataLoader;
        this.validationService = validationService;
        this.contractMatchingService = contractMatchingService;
        this.ros2CodegenService = ros2CodegenService;
        this.recoveryService = recoveryService;
        this.capabilityNormalizer = capabilityNormalizer;
        this.mapper = mapper;
    }

    public PipelineResponse run(String text, ExecutionEvent event) {
        return run(text, event, RunLogger.buffered(UUID.randomUUID().toString(), log, mapper));
    }

    public PipelineResponse run(String text, ExecutionEvent event, RunLogger runLogger) {
        List<LlmTrace> traces = new ArrayList<>();
        BlackboardWorkspace blackboard = new BlackboardWorkspace(text, event, dataLoader.contracts());
        List<RobotContract> contracts = blackboard.contracts();

        Map<String, Object> blackboardInput = new LinkedHashMap<>();
        blackboardInput.put("text", blackboard.rawText());
        blackboardInput.put("event", blackboard.event());
        blackboardInput.put("contract_robot_count", contracts.size());
        blackboardInput.put("workspace", blackboard.snapshot());
        runLogger.input("Blackboard", "初始化版本化黑板", blackboardInput);

        runTaskIntentAgent(blackboard, traces, runLogger);
        runTaskGraphAgent(blackboard, traces, runLogger);
        runEvidenceBindingAgent(blackboard, traces, runLogger);
        boolean repairCalled = runValidationAndRepairAgents(blackboard, traces, runLogger);
        runContractMatchingTool(blackboard, runLogger);
        runRos2CodegenTool(blackboard, runLogger);
        runRecoveryAgents(blackboard, traces, runLogger);

        List<AgentStatus> agents = List.of(
                new AgentStatus("任务理解 Agent", "done", "TaskIntent", "read: RawTaskText; write: TaskIntent"),
                new AgentStatus("任务图生成 Agent", "done", "DraftTaskGraph", "read: RawTaskText + TaskIntent + CapabilityContracts; write: TaskGraph"),
                new AgentStatus("证据绑定 Agent", "done", "EvidenceTaskGraph", "read: RawTaskText + TaskGraph; write: TaskGraph"),
                new AgentStatus("规则校验工具", "done", "ValidationReport", "read: TaskGraph + CapabilityContracts; write: ValidationReport"),
                new AgentStatus("校验修复 Agent", repairCalled ? "done" : "skipped_or_not_needed", repairCalled ? "RevisedTaskGraph" : "NoRepairNeeded", "read: TaskGraph + ValidationReport; write: TaskGraph"),
                new AgentStatus("能力匹配与接口映射工具", "done", "SchedulePlan + InterfaceBinding", "read: TaskGraph + CapabilityContracts; write: ExecutionPlan"),
                new AgentStatus("ROS 2 代码生成工具", "done", "GeneratedRos2Code", "read: TaskGraph + ExecutionPlan; write: GeneratedRos2Code"),
                new AgentStatus("异常恢复 Agent", "done", "RecoveryPlan", "read: Event + TaskGraph + ExecutionPlan + CapabilityContracts; write: RecoveryPlan")
        );
        List<String> artifacts = List.of("TaskIntent", "EvidenceTaskGraph", "ValidationReport", "SchedulePlan", "InterfaceBinding", "GeneratedRos2Code", "RecoveryPlan", "BlackboardTrace");
        runLogger.output("Blackboard", "黑板协作完成，返回最终产物", Map.of("artifacts", artifacts, "workspace", blackboard.snapshot()));
        return new PipelineResponse(
                "blackboard_agents_java",
                traces.isEmpty() ? "deepseek" : traces.get(0).model(),
                agents,
                traces,
                blackboard.intent(),
                blackboard.taskGraph(),
                blackboard.validation(),
                blackboard.executionPlan(),
                blackboard.generatedCode(),
                blackboard.recoveryPlan(),
                runLogger.entries(),
                artifacts
        );
    }

    private void runTaskIntentAgent(BlackboardWorkspace blackboard, List<LlmTrace> traces, RunLogger runLogger) {
        String intentInput = "自然语言任务：\n" + blackboard.rawText();
        runLogger.input("TaskIntentAgent", "从黑板读取 RawTaskText，抽取目标/参与机器人/区域/条件", Map.of("read", List.of("RawTaskText"), "write", "TaskIntent", "prompt", intentInput, "schema", intentSchemaHint()));
        AgentResult intentResult = llm.callJsonAgent("TaskIntentAgent", intentPrompt(), intentInput, intentSchemaHint());
        traces.add(intentResult.trace());
        TaskIntent intent = mapper.convertValue(intentResult.data(), TaskIntent.class);
        blackboard.write("TaskIntentAgent", "TaskIntent", intent, "自然语言任务意图结构化");
        runLogger.output("TaskIntentAgent", "写入黑板 TaskIntent", intent);
    }

    private void runTaskGraphAgent(BlackboardWorkspace blackboard, List<LlmTrace> traces, RunLogger runLogger) {
        String capabilityHint = capabilityNormalizer.capabilityHint(blackboard.contracts());
        String graphInput = "原文：\n" + blackboard.rawText() + "\n\nTaskIntent：\n" + json(blackboard.intent()) + "\n\n可用标准能力：\n" + capabilityHint;
        runLogger.input("TaskGraphAgent", "从黑板读取 TaskIntent 与契约，生成初始任务图", Map.of("read", List.of("RawTaskText", "TaskIntent", "CapabilityContracts"), "write", "TaskGraph", "prompt", graphInput, "schema", graphSchemaHint()));
        AgentResult graphResult = llm.callJsonAgent("TaskGraphAgent", graphPrompt(), graphInput, graphSchemaHint());
        traces.add(graphResult.trace());
        TaskGraph graph = capabilityNormalizer.normalize(GraphSupport.mapToGraph(graphResult.data(), blackboard.rawText()), blackboard.contracts());
        blackboard.write("TaskGraphAgent", "TaskGraph", graph, "生成 DraftTaskGraph");
        runLogger.output("TaskGraphAgent", "写入黑板 DraftTaskGraph", graphSummary(graph));
    }

    private void runEvidenceBindingAgent(BlackboardWorkspace blackboard, List<LlmTrace> traces, RunLogger runLogger) {
        String evidenceInput = "原文：\n" + blackboard.rawText() + "\n\nTaskGraph：\n" + json(blackboard.taskGraph());
        runLogger.input("EvidenceBindingAgent", "从黑板读取 TaskGraph，校正 evidence_span", Map.of("read", List.of("RawTaskText", "TaskGraph"), "write", "TaskGraph", "prompt", evidenceInput, "schema", graphSchemaHint()));
        AgentResult evidenceResult = llm.callJsonAgent("EvidenceBindingAgent", evidencePrompt(), evidenceInput, graphSchemaHint());
        traces.add(evidenceResult.trace());
        TaskGraph graph = capabilityNormalizer.normalize(GraphSupport.mapToGraph(evidenceResult.data(), blackboard.rawText()), blackboard.contracts());
        blackboard.write("EvidenceBindingAgent", "TaskGraph", graph, "证据绑定后的 EvidenceTaskGraph");
        runLogger.output("EvidenceBindingAgent", "更新黑板 EvidenceTaskGraph", graphSummary(graph));
    }

    private boolean runValidationAndRepairAgents(BlackboardWorkspace blackboard, List<LlmTrace> traces, RunLogger runLogger) {
        runLogger.input("ValidationTool", "从黑板读取 EvidenceTaskGraph 与能力契约库，执行确定性校验", Map.of("read", List.of("TaskGraph", "CapabilityContracts"), "write", "ValidationReport", "graph", graphSummary(blackboard.taskGraph()), "contracts", contractSummary(blackboard.contracts())));
        ValidationReport validation = validationService.validate(blackboard.taskGraph(), blackboard.contracts());
        blackboard.write("ValidationTool", "ValidationReport", validation, "确定性任务图校验结果");
        runLogger.output("ValidationTool", "写入黑板 ValidationReport", validation);
        if (validation.overall_valid()) {
            runLogger.info("ValidationRepairAgent", "校验通过，跳过修复 Agent", Map.of("reason", "overall_valid=true"));
            return false;
        }

        String repairInput = "原文：\n" + blackboard.rawText() + "\n\n当前 TaskGraph：\n" + json(blackboard.taskGraph()) + "\n\nValidationReport：\n" + json(validation);
        runLogger.input("ValidationRepairAgent", "从黑板读取校验错误，按 repair_scope 局部修复任务图", Map.of("read", List.of("RawTaskText", "TaskGraph", "ValidationReport"), "write", "TaskGraph", "prompt", repairInput, "schema", graphSchemaHint()));
        AgentResult repairResult = llm.callJsonAgent("ValidationRepairAgent", repairPrompt(), repairInput, graphSchemaHint());
        traces.add(repairResult.trace());
        TaskGraph graph = capabilityNormalizer.normalize(GraphSupport.mapToGraph(repairResult.data(), blackboard.rawText()), blackboard.contracts());
        blackboard.write("ValidationRepairAgent", "TaskGraph", graph, "按 ValidationReport 局部修复后的 RevisedTaskGraph");
        runLogger.output("ValidationRepairAgent", "更新黑板 RevisedTaskGraph", graphSummary(graph));

        runLogger.input("ValidationTool", "对修复后的任务图再次校验", Map.of("read", List.of("TaskGraph", "CapabilityContracts"), "write", "ValidationReport", "graph", graphSummary(graph)));
        validation = validationService.validate(graph, blackboard.contracts());
        blackboard.write("ValidationTool", "ValidationReport", validation, "修复后的二次校验结果");
        runLogger.output("ValidationTool", "更新黑板二次 ValidationReport", validation);
        return true;
    }

    private void runContractMatchingTool(BlackboardWorkspace blackboard, RunLogger runLogger) {
        runLogger.input("ContractMatchingTool", "从黑板读取有效任务图与能力契约，生成接口绑定和调度顺序", Map.of("read", List.of("TaskGraph", "CapabilityContracts"), "write", "ExecutionPlan", "graph", graphSummary(blackboard.taskGraph()), "contracts", contractSummary(blackboard.contracts())));
        ExecutionPlan plan = contractMatchingService.buildPlan(blackboard.taskGraph(), blackboard.contracts());
        blackboard.write("ContractMatchingTool", "ExecutionPlan", plan, "契约匹配、接口绑定与调度计划");
        runLogger.output("ContractMatchingTool", "写入黑板 SchedulePlan 与 InterfaceBinding", Map.of(
                "binding_count", plan.bindings().size(),
                "unmapped", plan.unmapped(),
                "schedule", plan.schedule()
        ));
    }

    private void runRos2CodegenTool(BlackboardWorkspace blackboard, RunLogger runLogger) {
        runLogger.input("Ros2CodegenTool", "从黑板读取 SchedulePlan 与 InterfaceBinding，生成 ROS 2 Python 执行骨架", Map.of("read", List.of("TaskGraph", "ExecutionPlan"), "write", "GeneratedRos2Code", "schedule", blackboard.executionPlan().schedule(), "bindings", blackboard.executionPlan().bindings()));
        GeneratedRos2Code generatedCode = ros2CodegenService.generate(blackboard.taskGraph(), blackboard.executionPlan());
        blackboard.write("Ros2CodegenTool", "GeneratedRos2Code", generatedCode, "ROS 2 Python 执行骨架");
        runLogger.output("Ros2CodegenTool", "写入黑板 GeneratedRos2Code", Map.of(
                "language", generatedCode.language(),
                "entrypoint", generatedCode.entrypoint(),
                "files", generatedCode.files(),
                "code_lines", generatedCode.code().lines().count()
        ));
    }

    private void runRecoveryAgents(BlackboardWorkspace blackboard, List<LlmTrace> traces, RunLogger runLogger) {
        ExecutionEvent actualEvent = blackboard.event() == null ? defaultEvent(blackboard.taskGraph(), blackboard.executionPlan()) : blackboard.event();
        blackboard.write("EventMonitor", "ExecutionEvent", actualEvent, blackboard.event() == null ? "生成默认演示异常事件" : "使用外部输入异常事件");
        String recoveryInput = "TaskGraph：\n" + json(blackboard.taskGraph()) + "\n\n当前计划：\n" + json(blackboard.executionPlan()) + "\n\n异常事件：\n" + json(actualEvent);
        runLogger.input("RecoveryStrategyAgent", "从黑板读取任务图/计划/异常事件，生成局部恢复策略", Map.of("read", List.of("TaskGraph", "ExecutionPlan", "ExecutionEvent"), "write", "RecoveryStrategy", "prompt", recoveryInput, "schema", recoverySchemaHint()));
        AgentResult strategyResult = llm.callJsonAgent("RecoveryStrategyAgent", recoveryPrompt(), recoveryInput, recoverySchemaHint());
        traces.add(strategyResult.trace());
        RecoveryStrategy strategy = mapper.convertValue(strategyResult.data(), RecoveryStrategy.class);
        blackboard.write("RecoveryStrategyAgent", "RecoveryStrategy", strategy, "局部恢复策略说明");
        runLogger.output("RecoveryStrategyAgent", "写入黑板 RecoveryStrategy", strategy);
        runLogger.input("RecoveryTool", "从黑板读取恢复策略，执行受影响子图计算与机器人重绑定", Map.of("read", List.of("TaskGraph", "ExecutionPlan", "ExecutionEvent", "CapabilityContracts", "RecoveryStrategy"), "write", "RecoveryPlan", "event", actualEvent, "strategy", strategy));
        RecoveryPlan recovery = recoveryService.recover(blackboard.taskGraph(), blackboard.executionPlan(), actualEvent, blackboard.contracts(), strategy);
        blackboard.write("RecoveryTool", "RecoveryPlan", recovery, "最小扰动恢复计划");
        runLogger.output("RecoveryTool", "写入黑板 RecoveryPlan", recovery);
    }

    private Map<String, Object> graphSummary(TaskGraph graph) {
        return Map.of(
                "node_count", graph.nodes().size(),
                "edge_count", graph.edges().size(),
                "constraint_count", graph.constraints().size(),
                "nodes", graph.nodes(),
                "edges", graph.edges(),
                "evidence_coverage", graph.evidence_coverage()
        );
    }

    private List<Map<String, Object>> contractSummary(List<RobotContract> contracts) {
        return contracts.stream().map(contract -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("robot_id", contract.robot_id());
            item.put("robot_type", contract.robot_type());
            item.put("capabilities", contract.capabilities());
            item.put("state", contract.state());
            return item;
        }).toList();
    }

    private ExecutionEvent defaultEvent(TaskGraph graph, ExecutionPlan plan) {
        InterfaceBinding ugv = plan.bindings().stream().filter(b -> "UGV-01".equals(b.robot_id())).findFirst()
                .orElse(plan.bindings().stream().filter(b -> "UGV".equals(b.robot_type())).findFirst().orElse(null));
        List<String> completed = graph.nodes().stream().filter(n -> "UAV".equals(n.actor_type())).map(TaskNode::id).toList();
        return new ExecutionEvent("low_battery", ugv == null ? "UGV-01" : ugv.robot_id(), ugv == null ? null : ugv.node_id(), ugv == null ? "/ugv_01/recheck_area" : ugv.ros_interface().name(), completed, "UGV battery below 20% before recheck");
    }

    private String json(Object value) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String intentPrompt() { return "你是异构多机器人任务理解 Agent。只负责从自然语言中抽取任务目标、机器人类型、对象、区域和条件/异常触发词。不要生成任务图，不要分配机器人。"; }
    private String graphPrompt() { return "你是证据约束任务图生成 Agent。根据 TaskIntent、原文和可用标准能力生成 TaskGraph。节点只表示 UAV/UGV/DOG 的可执行机器人任务；task_type 必须优先从可用标准能力中选择，不要输出 enter/retreat/approach/detect 等开放式动词；发现异常、异常检测、电量检测等系统判定不要生成 SYSTEM 节点，应表达为 condition/recovery 边；边表达 sequence/parallel/condition/recovery；evidence 必须填写原文连续短语；无法确定的参数写入 missing_fields；不要做具体机器人实例分配。"; }
    private String evidencePrompt() { return "你是证据绑定 Agent。检查每个节点、边、约束的 evidence 是否为原文连续片段；如不是，请替换为最接近的原文连续短语。不得改动任务语义。"; }
    private String repairPrompt() { return "你是校验修复 Agent。只能根据 ValidationReport 修复 repair_scope 内的问题，不要整体重写任务图。修复后仍需保持 evidence 为原文连续片段。"; }
    private String recoveryPrompt() { return "你是异常恢复 Agent。根据任务图、当前计划和异常事件，给出局部恢复策略说明。你不直接改接口参数，具体重绑定由后端工具完成。"; }

    private String intentSchemaHint() { return "{goal:string, actors:{UAV:boolean,UGV:boolean,DOG:boolean}, objects:string[], areas:string[], conditions:[{type:string,evidence_text:string}], raw_text:string}"; }
    private String graphSchemaHint() { return "{nodes:[{id,task_type,actor_type,params,output,status,confidence,missing_fields,evidence}], edges:[{id,source,target,type,condition,evidence}], constraints:[{type,value,target_actor,evidence}]}"; }
    private String recoverySchemaHint() { return "{strategy:string, reasoning_summary:string, preferred_replacement_rules:string[], risk_notes:string[]}"; }
}
