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
    private final ObjectMapper mapper;

    public AgentOrchestrator(DeepSeekClient llm, DataLoader dataLoader, ValidationService validationService, ContractMatchingService contractMatchingService, Ros2CodegenService ros2CodegenService, RecoveryService recoveryService, ObjectMapper mapper) {
        this.llm = llm;
        this.dataLoader = dataLoader;
        this.validationService = validationService;
        this.contractMatchingService = contractMatchingService;
        this.ros2CodegenService = ros2CodegenService;
        this.recoveryService = recoveryService;
        this.mapper = mapper;
    }

    public PipelineResponse run(String text, ExecutionEvent event) {
        return run(text, event, RunLogger.buffered(UUID.randomUUID().toString(), log, mapper));
    }

    public PipelineResponse run(String text, ExecutionEvent event, RunLogger runLogger) {
        List<LlmTrace> traces = new ArrayList<>();
        List<RobotContract> contracts = dataLoader.contracts();

        Map<String, Object> pipelineInput = new LinkedHashMap<>();
        pipelineInput.put("text", text);
        pipelineInput.put("event", event);
        pipelineInput.put("contract_robot_count", contracts.size());
        runLogger.input("Pipeline", "收到自然语言任务", pipelineInput);

        String intentInput = "自然语言任务：\n" + text;
        runLogger.input("TaskIntentAgent", "输入原始任务文本，抽取目标/参与机器人/区域/条件", Map.of("prompt", intentInput, "schema", intentSchemaHint()));
        AgentResult intentResult = llm.callJsonAgent("TaskIntentAgent", intentPrompt(), intentInput, intentSchemaHint());
        traces.add(intentResult.trace());
        TaskIntent intent = mapper.convertValue(intentResult.data(), TaskIntent.class);
        runLogger.output("TaskIntentAgent", "输出 TaskIntent", intent);

        String graphInput = "原文：\n" + text + "\n\nTaskIntent：\n" + json(intent);
        runLogger.input("TaskGraphAgent", "输入原文与 TaskIntent，生成初始任务图", Map.of("prompt", graphInput, "schema", graphSchemaHint()));
        AgentResult graphResult = llm.callJsonAgent("TaskGraphAgent", graphPrompt(), graphInput, graphSchemaHint());
        traces.add(graphResult.trace());
        TaskGraph graph = GraphSupport.mapToGraph(graphResult.data(), text);
        runLogger.output("TaskGraphAgent", "输出 DraftTaskGraph", graphSummary(graph));

        String evidenceInput = "原文：\n" + text + "\n\nTaskGraph：\n" + json(graph);
        runLogger.input("EvidenceBindingAgent", "输入初始任务图，校正 evidence_span", Map.of("prompt", evidenceInput, "schema", graphSchemaHint()));
        AgentResult evidenceResult = llm.callJsonAgent("EvidenceBindingAgent", evidencePrompt(), evidenceInput, graphSchemaHint());
        traces.add(evidenceResult.trace());
        graph = GraphSupport.mapToGraph(evidenceResult.data(), text);
        runLogger.output("EvidenceBindingAgent", "输出 EvidenceTaskGraph", graphSummary(graph));

        runLogger.input("ValidationTool", "输入 EvidenceTaskGraph 与能力契约库，执行确定性校验", Map.of("graph", graphSummary(graph), "contracts", contractSummary(contracts)));
        ValidationReport validation = validationService.validate(graph, contracts);
        runLogger.output("ValidationTool", "输出 ValidationReport", validation);
        boolean repairCalled = false;
        if (!validation.overall_valid()) {
            repairCalled = true;
            String repairInput = "原文：\n" + text + "\n\n当前 TaskGraph：\n" + json(graph) + "\n\nValidationReport：\n" + json(validation);
            runLogger.input("ValidationRepairAgent", "输入校验错误，按 repair_scope 局部修复任务图", Map.of("prompt", repairInput, "schema", graphSchemaHint()));
            AgentResult repairResult = llm.callJsonAgent("ValidationRepairAgent", repairPrompt(), repairInput, graphSchemaHint());
            traces.add(repairResult.trace());
            graph = GraphSupport.mapToGraph(repairResult.data(), text);
            runLogger.output("ValidationRepairAgent", "输出 RevisedTaskGraph", graphSummary(graph));
            runLogger.input("ValidationTool", "对修复后的任务图再次校验", graphSummary(graph));
            validation = validationService.validate(graph, contracts);
            runLogger.output("ValidationTool", "输出二次 ValidationReport", validation);
        } else {
            runLogger.info("ValidationRepairAgent", "校验通过，跳过修复 Agent", Map.of("reason", "overall_valid=true"));
        }

        runLogger.input("ContractMatchingTool", "输入有效任务图与能力契约，生成接口绑定和调度顺序", Map.of("graph", graphSummary(graph), "contracts", contractSummary(contracts)));
        ExecutionPlan plan = contractMatchingService.buildPlan(graph, contracts);
        runLogger.output("ContractMatchingTool", "输出 SchedulePlan 与 InterfaceBinding", Map.of(
                "binding_count", plan.bindings().size(),
                "unmapped", plan.unmapped(),
                "schedule", plan.schedule()
        ));
        runLogger.input("Ros2CodegenTool", "输入 SchedulePlan 与 InterfaceBinding，生成 ROS 2 Python 执行骨架", Map.of("schedule", plan.schedule(), "bindings", plan.bindings()));
        GeneratedRos2Code generatedCode = ros2CodegenService.generate(graph, plan);
        runLogger.output("Ros2CodegenTool", "输出 GeneratedRos2Code", Map.of(
                "language", generatedCode.language(),
                "entrypoint", generatedCode.entrypoint(),
                "files", generatedCode.files(),
                "code_lines", generatedCode.code().lines().count()
        ));
        ExecutionEvent actualEvent = event == null ? defaultEvent(graph, plan) : event;
        String recoveryInput = "TaskGraph：\n" + json(graph) + "\n\n当前计划：\n" + json(plan) + "\n\n异常事件：\n" + json(actualEvent);
        runLogger.input("RecoveryStrategyAgent", "输入任务图/计划/异常事件，生成局部恢复策略", Map.of("prompt", recoveryInput, "schema", recoverySchemaHint()));
        AgentResult strategyResult = llm.callJsonAgent("RecoveryStrategyAgent", recoveryPrompt(), recoveryInput, recoverySchemaHint());
        traces.add(strategyResult.trace());
        RecoveryStrategy strategy = mapper.convertValue(strategyResult.data(), RecoveryStrategy.class);
        runLogger.output("RecoveryStrategyAgent", "输出恢复策略", strategy);
        runLogger.input("RecoveryTool", "输入恢复策略，执行受影响子图计算与机器人重绑定", Map.of("event", actualEvent, "strategy", strategy));
        RecoveryPlan recovery = recoveryService.recover(graph, plan, actualEvent, contracts, strategy);
        runLogger.output("RecoveryTool", "输出 RecoveryPlan", recovery);

        List<AgentStatus> agents = List.of(
                new AgentStatus("任务理解 Agent", "done", "TaskIntent", "DeepSeek Chat Completions API"),
                new AgentStatus("任务图生成 Agent", "done", "DraftTaskGraph", "DeepSeek Chat Completions API"),
                new AgentStatus("证据绑定 Agent", "done", "EvidenceTaskGraph", "DeepSeek Chat Completions API + span normalization tool"),
                new AgentStatus("规则校验工具", "done", "ValidationReport", "deterministic validator"),
                new AgentStatus("校验修复 Agent", repairCalled ? "done" : "skipped_or_not_needed", repairCalled ? "RevisedTaskGraph" : "NoRepairNeeded", "DeepSeek Chat Completions API when needed"),
                new AgentStatus("能力匹配与接口映射工具", "done", "SchedulePlan + InterfaceBinding", "contract matching tool"),
                new AgentStatus("ROS 2 代码生成工具", "done", "GeneratedRos2Code", "deterministic code generator"),
                new AgentStatus("异常恢复 Agent", "done", "RecoveryPlan", "DeepSeek Chat Completions API + recovery tool")
        );
        List<String> artifacts = List.of("TaskIntent", "EvidenceTaskGraph", "ValidationReport", "SchedulePlan", "InterfaceBinding", "GeneratedRos2Code", "RecoveryPlan");
        runLogger.output("Pipeline", "闭环完成，返回最终产物", Map.of("artifacts", artifacts));
        return new PipelineResponse("real_llm_agents_java", traces.isEmpty() ? "deepseek" : traces.get(0).model(), agents, traces, intent, graph, validation, plan, generatedCode, recovery, runLogger.entries(), artifacts);
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
        List<String> completed = graph.nodes().stream().filter(n -> "UAV".equals(n.actor_type()) || "DOG".equals(n.actor_type())).map(TaskNode::id).toList();
        return new ExecutionEvent("low_battery", ugv == null ? "UGV-01" : ugv.robot_id(), ugv == null ? null : ugv.node_id(), ugv == null ? "/ugv_01/recheck_area" : ugv.ros_interface().name(), completed, "UGV battery below 20% before recheck");
    }

    private String json(Object value) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String intentPrompt() { return "你是异构多机器人任务理解 Agent。只负责从自然语言中抽取任务目标、机器人类型、对象、区域和条件/异常触发词。不要生成任务图，不要分配机器人。"; }
    private String graphPrompt() { return "你是证据约束任务图生成 Agent。根据 TaskIntent 和原文生成 TaskGraph。节点只表示 UAV/UGV/DOG 的可执行机器人任务；发现异常、异常检测、电量检测等系统判定不要生成 SYSTEM 节点，应表达为 condition/recovery 边；边表达 sequence/parallel/condition/recovery；evidence 必须填写原文连续短语；无法确定的参数写入 missing_fields；不要做具体机器人实例分配。"; }
    private String evidencePrompt() { return "你是证据绑定 Agent。检查每个节点、边、约束的 evidence 是否为原文连续片段；如不是，请替换为最接近的原文连续短语。不得改动任务语义。"; }
    private String repairPrompt() { return "你是校验修复 Agent。只能根据 ValidationReport 修复 repair_scope 内的问题，不要整体重写任务图。修复后仍需保持 evidence 为原文连续片段。"; }
    private String recoveryPrompt() { return "你是异常恢复 Agent。根据任务图、当前计划和异常事件，给出局部恢复策略说明。你不直接改接口参数，具体重绑定由后端工具完成。"; }

    private String intentSchemaHint() { return "{goal:string, actors:{UAV:boolean,UGV:boolean,DOG:boolean}, objects:string[], areas:string[], conditions:[{type:string,evidence_text:string}], raw_text:string}"; }
    private String graphSchemaHint() { return "{nodes:[{id,task_type,actor_type,params,output,status,confidence,missing_fields,evidence}], edges:[{id,source,target,type,condition,evidence}], constraints:[{type,value,target_actor,evidence}]}"; }
    private String recoverySchemaHint() { return "{strategy:string, reasoning_summary:string, preferred_replacement_rules:string[], risk_notes:string[]}"; }
}
