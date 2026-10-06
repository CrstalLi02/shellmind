package com.shellmind.cases.react.engine;

import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.domain.agent.service.engine.AgentLoopConfig;
import com.shellmind.domain.agent.service.engine.ContextCompressor;
import com.shellmind.domain.agent.service.engine.LoopState;
import com.shellmind.domain.agent.adapter.repository.IAgentRunRepository;
import com.shellmind.domain.agent.model.entity.AgentRunEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent loop executor (supervisor mode).
 * <p>Aligned with ShellMind streamingAgent.ts.
 * <p>
 * Responsibilities (does not enter the node chain):
 * 1. Wrap rootNode.apply() and provide outer-layer supervision
 * 2. Idle-timeout detection (30s poll; cancel node-chain execution on timeout)
 * 3. 413 Payload Too Large → compress context, then retry the whole node chain
 * 4. Token-budget tracking and warnings
 * 5. Dead-loop detection via step and tool-call patterns in DynamicContext
 * 6. External cancel control (user stop / client disconnect)
 * <p>
 * Relationship to the existing ReAct node chain:
 * AgentLoopExecutor is the supervisor; the node chain is the executor.
 * The node chain (RootNode → AiCallNode → ToolCallNode → LoopDecisionNode → UserFeedbackNode)
 * keeps its original logic. AgentLoopExecutor adds, from the outside:
 * - An async idle-detection thread
 * - 413 retry (re-run the entire node chain)
 * - Token-budget warning
 * - Unified error fallback
 *
 * @author Teaching Edition - ShellMind
 * 2026/6/22
 */
@Slf4j
@Component
public class AgentLoopExecutor {

    /** Idle-check interval in seconds. */
    private static final long IDLE_CHECK_INTERVAL_SECONDS = 30;

    /** Max consecutive 413 errors before giving up. */
    private static final int MAX_CONSECUTIVE_413 = 3;

    @Resource
    private ContextCompressor contextCompressor;

    @Resource
    private IAgentRunRepository agentRunRepository;

    /**
     * Execute the agent loop in supervisor mode.
     * <p>
     * Wraps rootNode.apply(), runs the node chain on an async thread,
     * and runs idle-timeout detection plus cancel control on the main thread.
     *
     * @param config         loop config
     * @param dynamicContext ReAct dynamic context (already initialized)
     * @param requestDTO     request params
     * @param nodeChainTask  node-chain execution logic (rootNode.apply)
     * @return final result
     */
    public ReActResultDTO executeSupervised(
            AgentLoopConfig config,
            DefaultReActFactory.DynamicContext dynamicContext,
            ChatRequestDTO requestDTO,
            NodeChainTask nodeChainTask) {

        log.info("AgentLoopExecutor start supervising: mode={}, maxRounds={}, idleTimeoutMs={}, maxTokenBudget={}",
                config.getMode(), config.getMaxRounds(), config.getIdleTimeoutMs(), config.getMaxTokenBudget());

        // 1. Create loop state
        LoopState state = LoopState.init(config);
        state.setRunId(java.util.UUID.randomUUID().toString());
        state.setSessionId(dynamicContext.getSessionId());
        state.setUserId(dynamicContext.getUserId());
        state.setAgentId(dynamicContext.getAgentId());
        state.setMessageHistory(dynamicContext.getMessageHistory());
        state.touch();
        agentRunRepository.save(AgentRunEntity.from(state));

        // Expose LoopState on DynamicContext so the heartbeat thread can call touch() to refresh last-active time
        dynamicContext.setLoopState(state);

        // 2. Create callbacks (bridge to the SSE emitter)
        AgentLoopCallbacks callbacks = createCallbacks(dynamicContext);

        // 3. Start the idle-timeout detection thread
        ScheduledExecutorService idleChecker = startIdleChecker(state, dynamicContext, callbacks);

        // 4. Run the node chain (with 413 retry)
        ReActResultDTO result = null;
        int attempt413 = 0;

        try {
            while (attempt413 <= MAX_CONSECUTIVE_413) {
                try {
                    // run the node chain
                    result = nodeChainTask.execute(requestDTO, dynamicContext);
                    state.touch();

            // Normal completion: copy the node chain's stopReason from dynamicContext onto state.
            // Otherwise resolveStatus would mis-read RUNNING (finishedAt set, but stopReason still null so the status is not terminal).
            if (state.getStopReason() == null) {
                state.setStopReason(dynamicContext.getStopReason() != null ? dynamicContext.getStopReason() : "completed");
            }
            state.markCompleted();
            break;

                } catch (Exception e) {
                    state.touch();

                    if (is413Error(e) && attempt413 < MAX_CONSECUTIVE_413) {
                        attempt413++;
                        log.warn("413 Payload Too Large, emergency-compress and retry ({}/{})",
                                attempt413, MAX_CONSECUTIVE_413);

                        callbacks.onWarning("Context is too long; compressing and retrying...");

                        // Emergency-compress context with ContextCompressor
                        List<Map<String, Object>> compressed = contextCompressor.emergencyCompress(
                                dynamicContext.getMessageHistory());
                        if (compressed != null && compressed.size() < dynamicContext.getMessageHistory().size()) {
                            dynamicContext.setMessageHistory(new java.util.ArrayList<>(compressed));
                            state.setMessageHistory(dynamicContext.getMessageHistory());
                            // Reset DynamicContext loop state, then retry
                            dynamicContext.setCurrentStep(new java.util.concurrent.atomic.AtomicInteger(0));
                            dynamicContext.resetRoundBuffers();
                            dynamicContext.setStopReason(null);
                            dynamicContext.setErrorMessage(null);
                            continue;
                        } else {
                            callbacks.onError("Context compression failed; cannot recover");
                            state.setStopReason("compress_failed");
                            state.markFailed();
                            break;
                        }
                    }

                    // Non-413 error: stop immediately
                    if (!state.isCancelled()) {
                        callbacks.onError("Execution exception: " + e.getMessage());
                        state.setStopReason("error");
                        state.markFailed();
                    }
                    break;
                }
            }

            // 5. If there is no result (abnormal exit), build a default result
            if (result == null) {
                result = buildFallbackResult(dynamicContext, state);
            }

            // 6. Token budget warning
            state.refreshTokenEstimate();
            if (state.isTokenBudgetExceeded()) {
                try {
                    callbacks.onWarning("Token budget exceeded (" + state.getTotalTokens()
                            + " > " + config.getMaxTokenBudget() + "); consider optimizing context");
                } catch (Exception callbackException) {
                    log.warn("Failed to send token-budget warning", callbackException);
                }
            }

            // 7. Dead-loop detection based on the steps-to-tool-call ratio
            if (result.getTotalSteps() > 0 && result.getTotalToolCalls() > 0) {
                double toolCallPerStep = (double) result.getTotalToolCalls() / result.getTotalSteps();
                if (toolCallPerStep > config.getMaxToolCallsPerRound() * 2) {
                    log.warn("High tool-call density detected: {}/{} = {:.1f}/step",
                            result.getTotalToolCalls(), result.getTotalSteps(), toolCallPerStep);
                    callbacks.onWarning("High tool-call density detected; possible looping calls");
                }
            }

            // 8. Send the completion callback
            try {
                callbacks.onDone(result);
            } catch (Exception callbackException) {
                log.warn("Failed to send completion callback", callbackException);
            }

            log.info("AgentLoopExecutor supervision ended: steps={}, toolCalls={}, tokens={}, stopReason={}",
                    result.getTotalSteps(), result.getTotalToolCalls(), state.getTotalTokens(),
                    result.getStopReason());

            return result;

        } finally {
            idleChecker.shutdownNow();
            state.refreshTokenEstimate();
            agentRunRepository.save(AgentRunEntity.from(state));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  idle-timeout detection
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start the idle-timeout detection thread.
     */
    private ScheduledExecutorService startIdleChecker(
            LoopState state,
            DefaultReActFactory.DynamicContext dynamicContext,
            AgentLoopCallbacks callbacks) {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-idle-checker-" + dynamicContext.getSessionId());
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (state.isIdle() && !dynamicContext.isCancelled()) {
                    long idleMs = System.currentTimeMillis() - state.getLastActivityTime();
                    log.warn("Agent idle timeout: {}ms without activity (threshold {}ms)",
                            idleMs, state.getConfig().getIdleTimeoutMs());

                    state.setStopReason("idle_timeout");
                    dynamicContext.setStopReason("idle_timeout");
                    dynamicContext.markCancelled("idle_timeout");

                    callbacks.onWarning("Idle timeout (" + (idleMs / 1000) + "s); stopping automatically");
                }
            } catch (Exception e) {
                log.error("Idle-check thread exception", e);
            }
        }, IDLE_CHECK_INTERVAL_SECONDS, IDLE_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

        return scheduler;
    }

    // ═══════════════════════════════════════════════════════════════
    //  callback creation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create the SSE callback bridge.
     */
    private AgentLoopCallbacks createCallbacks(DefaultReActFactory.DynamicContext dynamicContext) {
        ClientChannel emitter = dynamicContext.getEmitter();

        return new AgentLoopCallbacks() {
            @Override
            public void onText(String chunk, String fullText) {
                // Text events are sent directly by the node chain; do not repeat them here
            }

            @Override
            public void onToolCall(String toolCallId, String toolName, String args) {
                // tool-call events are sent directly by the node chain
            }

            @Override
            public void onToolProgress(String toolCallId, String progress) {
                // Tool-progress events are sent directly by SseToolProgressNotifier
            }

            @Override
            public void onToolResult(String toolCallId, String content, String status) {
                // tool-result events are sent directly by the node chain
            }

            @Override
            public void onRoundEnd(int currentRound, int maxRounds, int totalToolCalls) {
                // round-end events are sent directly by the node chain
            }

            @Override
            public void onWarning(String message) {
                try {
                    String json = "{\"event\":\"warning\",\"content\":\""
                            + message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                            + "\"}\n";
                    emitter.send(json);
                    log.info("Sending warning event: {}", message);
                } catch (Exception e) {
                    log.warn("Failed to send warning event: {}", e.getMessage());
                }
            }

            @Override
            public void onError(String message) {
                try {
                    String json = "{\"event\":\"error\",\"content\":\""
                            + message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                            + "\"}\n";
                    emitter.send(json);
                    log.error("Sending error event: {}", message);
                } catch (Exception e) {
                    log.warn("Failed to send error event: {}", e.getMessage());
                }
            }

            @Override
            public void onDone(ReActResultDTO result) {
                // Done events are sent directly by UserFeedbackNode
                log.info("Agent loop complete: stopReason={}", result.getStopReason());
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Whether this exception is a 413 error.
     */
    private boolean is413Error(Exception e) {
        if (e == null) return false;
        String message = e.getMessage();
        if (message == null) return false;
        return message.contains("413")
                || message.contains("Payload Too Large")
                || message.contains("Request Entity Too Large")
                || message.contains("content too long")
                || message.contains("context length exceeded");
    }

    /**
     * Build a fallback result for exception cases.
     */
    private ReActResultDTO buildFallbackResult(
            DefaultReActFactory.DynamicContext dynamicContext,
            LoopState state) {

        String stopReason = state.getStopReason();
        if (stopReason == null) {
            stopReason = dynamicContext.isCancelled() ? "user_stop" : "error";
        }

        String content = dynamicContext.getAssistantContent() != null
                ? com.shellmind.cases.react.util.MarkdownNormalizer.normalize(
                        dynamicContext.getAssistantContent().toString())
                : "";

        return ReActResultDTO.builder()
                .content(content)
                .totalSteps(dynamicContext.getStep())
                .totalToolCalls(dynamicContext.getResult() != null
                        ? dynamicContext.getResult().getTotalToolCalls() : 0)
                .maxStepsReached("max_steps".equals(stopReason))
                .userStopped("user_stop".equals(stopReason))
                .idleTimeout("idle_timeout".equals(stopReason))
                .stopReason(stopReason)
                .error(state.getCancelReason())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Node-chain execution interface
    // ═══════════════════════════════════════════════════════════════

    /**
     * Functional interface for node-chain execution.
     * The caller implements this and runs rootNode.apply().
     */
    @FunctionalInterface
    public interface NodeChainTask {
        ReActResultDTO execute(ChatRequestDTO requestDTO, DefaultReActFactory.DynamicContext dynamicContext) throws Exception;
    }
}
