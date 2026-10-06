package com.shellmind.cases.react.node;

import com.shellmind.domain.agent.adapter.port.AgentRuntime;
import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.cases.react.AbstractAIAgentReActSupport;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.domain.conversation.adapter.repository.IChatHistoryRepository;
import com.shellmind.domain.conversation.model.entity.ChatMessageEntity;
import com.shellmind.domain.conversation.model.entity.ChatSessionEntity;
import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.domain.agent.model.valobj.prompt.TaskModeVO;
import com.shellmind.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.shellmind.domain.agent.service.execution.ExecutionPolicyResolver;
import com.shellmind.domain.agent.service.prompt.TaskModeClassifier;
import com.shellmind.domain.agent.model.valobj.execution.ExecutionPolicyVO;
import com.shellmind.types.design.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct Root Node
 *
 * <p>Responsibilities:
 * 1. Extract session params from ChatRequestDTO
 * 2. Initialize DynamicContext
 * 3. Record the terminal session ID (into DynamicContext; AiCallNode later puts it on RunContext)
 * 4. Route to AiCallNode
 *
 * <p>Node chain:
 * RootNode → AiCallNode → ToolCallNode → ToolResultNode → LoopDecisionNode
 *                                                   ↑__________________|
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4 13:57
 */
@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private AgentRuntime agentRuntime;

    @Resource
    private ExecutionPolicyResolver executionPolicyResolver;

    @Resource
    private TaskModeClassifier taskModeClassifier;

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;
    private static final int DEFAULT_MAX_AI_RETRIES = 2;
    private static final long DEFAULT_TOOL_TIMEOUT_MS = 60_000L;
    private static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 8000;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct RootNode - initialize context");

        // 1. Extract session params
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        String message = requestParameter.getMessage();

        String runtimeAgentId = agentId;
        if (requestParameter.getModelId() != null) {
            runtimeAgentId = agentRuntime.resolveAgent(agentId, requestParameter.getModelId());
        }

        // 2. Terminal session enters DynamicContext with the request and is built into RunContext each round (see AiCallNode)

        // [Phase 5] Auto-create/update session metadata
        ChatSessionEntity existingSession = chatHistoryRepository.getSession(sessionId);
        if (existingSession == null) {
            chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                    .id(sessionId)
                    .agentId(agentId)
                    .userId(userId)
                    .title(message != null && message.length() > 50 ? message.substring(0, 50) : message)
                    .messageCount(0)
                    .build());
            log.info("[Phase 5] Auto-created session record: sessionId={}, agentId={}", sessionId, agentId);
        }

        // 3. Initialize context
        dynamicContext.setSessionId(sessionId);
        dynamicContext.setUserId(userId);
        dynamicContext.setAgentId(runtimeAgentId);
        dynamicContext.setSourceAgentId(agentId);
        dynamicContext.setModelId(requestParameter.getModelId());
        dynamicContext.setTerminalSessionId(terminalSessionId);
        dynamicContext.setProjectContext(requestParameter.getProjectContext());
        dynamicContext.setCurrentToolCalls(new java.util.ArrayList<>());
        dynamicContext.setCurrentToolResults(new java.util.ArrayList<>());

        // [Phase 5] Load history from the database
        java.util.List<java.util.Map<String, Object>> history = new java.util.ArrayList<>();
        java.util.List<ChatMessageEntity> recentMessages = chatHistoryRepository.getRecentMessages(sessionId, 50);
        for (ChatMessageEntity msg : recentMessages) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("role", msg.getRole());
            map.put("content", msg.getContent() != null ? msg.getContent() : "");
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                map.put("tool_call_id", msg.getToolCallId());
                map.put("name", msg.getToolName());
            }
            history.add(map);
        }
        dynamicContext.setMessageHistory(history);
        dynamicContext.setCurrentStep(new java.util.concurrent.atomic.AtomicInteger(0));

        // [2026-09-28 architecture simplification] Removed LLM intent-routing:
        // Intent classification (rules or LLM) hurt execution routing more than it helped —
        // short-phrase misclassifications triggered read-only blocks, added an extra LLM call, and the model did not need the label.
        // Now always use the full execution budget from agent YAML, and let the model decide read-only vs write from
        // the instruction rules (only the model itself can tell that "start improving" means actually edit code).
        ExecutionPolicyVO executionPolicy = resolveUnifiedPolicy(agentId);

        String priorUserInstructions = history.stream()
                .filter(item -> "user".equals(item.get("role")) && item.get("content") != null)
                .map(item -> String.valueOf(item.get("content")))
                .collect(java.util.stream.Collectors.joining("\n---\n"));
        TaskModeVO taskMode = taskModeClassifier.classify(message, priorUserInstructions);

        dynamicContext.setCurrentIntent("UNIFIED");
        dynamicContext.setCurrentIntentConfidence(1.0D);
        dynamicContext.setMaxSteps(executionPolicy.maxSteps());
        dynamicContext.setMaxToolCalls(executionPolicy.maxToolCalls());
        dynamicContext.setMaxToolCallsPerRound(executionPolicy.maxToolCallsPerRound());
        dynamicContext.setMaxAiRetries(executionPolicy.maxAiRetries());
        dynamicContext.setToolTimeoutMs(executionPolicy.toolTimeoutMs());
        dynamicContext.setContextTokenBudget(executionPolicy.contextTokenBudget());
        dynamicContext.setTaskMode(taskMode);
        dynamicContext.setReadOnlyExecution(executionPolicy.readOnly() || taskMode == TaskModeVO.STATUS);
        dynamicContext.resetAiRetryCount();

        log.info("ReAct budget: intent=UNIFIED(intent routing removed), taskMode={}, maxSteps={}, maxToolCalls={}, perRound={}, maxAiRetries={}, toolTimeoutMs={}, contextTokenBudget={}, readOnly={}",
                taskMode,
                executionPolicy.maxSteps(),
                executionPolicy.maxToolCalls(),
                executionPolicy.maxToolCallsPerRound(),
                executionPolicy.maxAiRetries(),
                executionPolicy.toolTimeoutMs(),
                executionPolicy.contextTokenBudget(),
                executionPolicy.readOnly());

        // 4. Initialize the result DTO
        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        dynamicContext.setResult(result);

        // 5. Append the user message to history
        dynamicContext.appendUserMessage(message);

        log.info("ReAct RootNode - init complete sessionId={}, userId={}, agentId={}, terminalSessionId={}",
                sessionId, userId, agentId, terminalSessionId);

        // 6. Route to the AI-call node
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // RootNode → TaskBreakdownNode (detect whether a split is needed) → AiCallNode
        return getBean("reactTaskBreakdownNode");
    }

    /**
     * Read reactBudget from agent YAML
     */
    private AiAgentConfigTableVO.Module.ReactBudget resolveReactBudget(String agentId) {
        if (agentId == null || aiAgentAutoConfigProperties.getTables() == null) {
            return null;
        }
        for (AiAgentConfigTableVO table : aiAgentAutoConfigProperties.getTables().values()) {
            if (table.getAgent() != null && agentId.equals(table.getAgent().getAgentId())) {
                if (table.getModule() != null && table.getModule().getRunner() != null) {
                    return table.getModule().getRunner().getReactBudget();
                }
            }
        }
        return null;
    }

    /**
     * Unified execution budget: prefer reactBudget from agent YAML;
     * if unset, use a generous default (no intent split, not read-only).
     */
    private ExecutionPolicyVO resolveUnifiedPolicy(String agentId) {
        AiAgentConfigTableVO.Module.ReactBudget budget = resolveReactBudget(agentId);
        if (budget != null) {
            return new ExecutionPolicyVO(
                    budget.getMaxSteps() != null ? budget.getMaxSteps() : 80,
                    budget.getMaxToolCalls() != null ? budget.getMaxToolCalls() : 300,
                    budget.getMaxToolCallsPerRound() != null ? budget.getMaxToolCallsPerRound() : 200,
                    budget.getMaxAiRetries() != null ? budget.getMaxAiRetries() : 3,
                    budget.getToolTimeoutMs() != null ? budget.getToolTimeoutMs() : 90_000L,
                    budget.getContextTokenBudget() != null && budget.getContextTokenBudget() > 0
                            ? budget.getContextTokenBudget() : 16_000,
                    false
            );
        }
        return new ExecutionPolicyVO(80, 300, 200, 3, 90_000L, 16_000, false);
    }

}
