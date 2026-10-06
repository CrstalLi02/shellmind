package com.shellmind.cases.react.node;

import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActEventDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.api.dto.TaskBreakdownDTO;
import com.shellmind.cases.react.AbstractAIAgentReActSupport;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.domain.agent.model.valobj.task.TaskBreakdownVO;
import com.shellmind.domain.agent.service.ITaskBreakdownService;
import com.shellmind.types.design.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * Task-breakdown node
 *
 * <p>Inserted as: RootNode → TaskBreakdownNode → AiCallNode
 *
 * <p>Responsibilities:
 * 1. Detect whether the user request needs a task split
 * 2. If so, call the LLM to generate a plan
 * 3. Send a task_breakdown SSE event to the frontend
 * 4. Inject the plan into context for later AiCallNode use
 *
 * @author shellmind dev
 * 2026/6/19
 */
@Slf4j
@Component("reactTaskBreakdownNode")
public class TaskBreakdownNode extends AbstractAIAgentReActSupport {

    @Resource
    private ITaskBreakdownService taskBreakdownService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter,
                                      DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        String userMessage = requestParameter.getMessage();
        String sessionId = dynamicContext.getSessionId();

        log.info("ReAct TaskBreakdownNode - detect task breakdown: sessionId={}", sessionId);

        // 1. Detect whether a split is needed
        boolean needBreakdown = taskBreakdownService.shouldBreakdown(userMessage, sessionId);

        if (!needBreakdown) {
            log.info("No split needed; route directly to AiCallNode");
            return router(requestParameter, dynamicContext);
        }

        // 2. Run the split (prefer the user-configured model)
        TaskBreakdownVO breakdownVO = taskBreakdownService.breakdown(
                userMessage,
                sessionId,
                dynamicContext.getAgentId(),
                dynamicContext.getMessageHistory(),
                dynamicContext.getModelId()
        );

        // 3. Skip if the split result is empty (the task is actually simple)
        if (breakdownVO.getSubTasks() == null || breakdownVO.getSubTasks().isEmpty()) {
            log.info("Empty split result; task is simple; route to AiCallNode");
            return router(requestParameter, dynamicContext);
        }

        // 4. VO → DTO conversion
        TaskBreakdownDTO breakdownDTO = convertToDTO(breakdownVO);

        // 5. Send the split proposal over SSE
        sendTaskBreakdownEvent(dynamicContext.getEmitter(), breakdownDTO);

        // 6. Inject the plan into the user message (so the AI executes more planfully)
        String enrichedMessage = buildBreakdownEnhancedMessage(userMessage, breakdownVO);
        requestParameter.setMessage(enrichedMessage);

        log.info("Task breakdown done; injected {} subtasks into the message; routing to AiCallNode",
                breakdownVO.getSubTasks().size());

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * VO → DTO conversion
     */
    private TaskBreakdownDTO convertToDTO(TaskBreakdownVO vo) {
        List<TaskBreakdownDTO.SubTask> subTasks = new ArrayList<>();
        for (TaskBreakdownVO.SubTask st : vo.getSubTasks()) {
            subTasks.add(TaskBreakdownDTO.SubTask.builder()
                    .index(st.getIndex())
                    .title(st.getTitle())
                    .description(st.getDescription())
                    .expectedTools(st.getExpectedTools())
                    .status(st.getStatus())
                    .result(st.getResult())
                    .build());
        }
        return TaskBreakdownDTO.builder()
                .originalRequest(vo.getOriginalRequest())
                .subTasks(subTasks)
                .needConfirmation(vo.isNeedConfirmation())
                .summary(vo.getSummary())
                .build();
    }

    /**
     * Send task-breakdown SSE event
     */
    private void sendTaskBreakdownEvent(ClientChannel emitter, TaskBreakdownDTO breakdown) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("task_breakdown");
            event.setTaskBreakdown(breakdown);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending task_breakdown event: {} subtasks", breakdown.getSubTasks().size());
        } catch (Exception e) {
            log.warn("Failed to send task_breakdown event: {}", e.getMessage());
        }
    }

    /**
     * Inject the breakdown plan into the user message
     */
    private String buildBreakdownEnhancedMessage(String originalMessage, TaskBreakdownVO breakdown) {
        StringBuilder sb = new StringBuilder(originalMessage);
        sb.append("\n\n---\n📋 Task breakdown plan:\n");

        for (TaskBreakdownVO.SubTask st : breakdown.getSubTasks()) {
            sb.append(st.getIndex()).append(". **").append(st.getTitle()).append("**");
            if (st.getDescription() != null && !st.getDescription().isEmpty()) {
                sb.append(" - ").append(st.getDescription());
            }
            if (st.getExpectedTools() != null && !st.getExpectedTools().isEmpty()) {
                sb.append(" (tools: ").append(st.getExpectedTools()).append(")");
            }
            sb.append("\n");
        }

        sb.append("\nExecute the subtasks in order. After each one, briefly report the result, then continue to the next.\n---");
        return sb.toString();
    }

}
