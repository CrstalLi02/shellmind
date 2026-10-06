package com.shellmind.cases.react.factory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct dynamic context.
 *
 * <p>See DefaultArmoryFactory.DynamicContext. Holds state for the ReAct execution chain:
 * - session info (sessionId, userId, agentId)
 * - message history (messages + toolResults)
 * - ReAct loop state (steps, tool-call counts)
 * - SSE emitter
 * - tool definitions (ToolCallback[])
 * - ADK Runner / Session
 *
 * <p>ReAct loop data flow:
 * <pre>
 * RootNode         → initialize context, bind SSE emitter
 * AiCallNode       → build the AI request, append the user message to history
 * ToolCallNode     → parse tool_calls, run tools, append results to history
 * LoopDecisionNode → decide whether to continue (max_steps / finish / no tool calls)
 * UserFeedbackNode → send the final result, complete SSE
 * </pre>
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4 14:26
 */
public class DefaultReActFactory {

    /**
     * dynamic context
     */
    @Slf4j
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // ══════════════════════════════════════════════════════════
        //  Session basics
        // ══════════════════════════════════════════════════════════

        /** Chat session ID. */
        private String sessionId;

        /** User ID. */
        private String userId;

        /** Agent ID. */
        private String agentId;

        /** Original agent ID from the request, before a model was selected. */
        private String sourceAgentId;

        /** Model-config ID chosen for this request. */
        private Long modelId;

        /** SSH terminal session ID. */
        private String terminalSessionId;

        /** Current project context, passed through from the frontend ChatRequestDTO. */
        private com.shellmind.api.dto.ProjectContextDTO projectContext;

        /** SSE event emitter */
        private ClientChannel emitter;

        // ══════════════════════════════════════════════════════════
        //  Message history (see ShellMind streamingAgent.ts)
        //  After each ReAct round, toolCalls + toolResults are appended here
        // ══════════════════════════════════════════════════════════

        /**
         * Message history.
         * Format: { role: "user"/"assistant"/"tool", content: "...", tool_call_id?: "..." }
         */
        @Builder.Default
        private List<Map<String, Object>> messageHistory = new ArrayList<>();

        /**
         * Tool-call list for the current round.
         */
        @Builder.Default
        private List<Map<String, Object>> currentToolCalls = new ArrayList<>();

        /**
         * Tool-result list for the current round.
         */
        @Builder.Default
        private List<Map<String, Object>> currentToolResults = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  ReAct loop state
        // ══════════════════════════════════════════════════════════

        /** Current step. */
        @Builder.Default
        private AtomicInteger currentStep = new AtomicInteger(0);

        /** Maximum number of steps. */
        private int maxSteps;

        /** Maximum total tool-call count. */
        private int maxToolCalls;

        /** Maximum tool-call count per round. */
        private int maxToolCallsPerRound;

        /** Maximum AI-call retry count. */
        @Builder.Default
        private int maxAiRetries = 2;

        /** Default tool-execution timeout in milliseconds. */
        @Builder.Default
        private long toolTimeoutMs = 60_000L;

        /** Token budget for message history. */
        @Builder.Default
        private int contextTokenBudget = 8000;

        /** Total tool calls. */
        @Builder.Default
        private AtomicInteger totalToolCallCount = new AtomicInteger(0);

        /** Tool-call count for the current round. */
        @Builder.Default
        private AtomicInteger roundToolCallCount = new AtomicInteger(0);

        /** Whether this request is forced into read-only execution. */
        @Builder.Default
        private boolean readOnlyExecution = false;

        /** Deterministic task mode of the current user input. */
        private com.shellmind.domain.agent.model.valobj.prompt.TaskModeVO taskMode;

        /** Tools skipped this round because the per-round cap was hit (for logging and diagnosis). */
        private int overflowToolCount;

        // ══════════════════════════════════════════════════════════
        //  AI response buffer (accumulates streaming text)
        // ══════════════════════════════════════════════════════════

        /** Accumulated text response. */
        @Builder.Default
        private StringBuilder assistantContent = new StringBuilder();

        /** Boundary index in assistantContent at the most recent tool call. */
        @Builder.Default
        private int assistantContentBoundary = 0;

        /** Accumulated reasoning_content. */
        @Builder.Default
        private StringBuilder assistantReasoning = new StringBuilder();

        /** reasoning_content received in the previous round (must be sent back to the API). */
        private String lastReasoningContent;

        // ══════════════════════════════════════════════════════════
        //  Interrupt state
        // ══════════════════════════════════════════════════════════

        /** Stop reason: user_stop / idle_timeout / max_steps / client_disconnect. */
        private String stopReason;

        /** Error message, if any. */
        private String errorMessage;

        /** Client-disconnect / task-cancel flag (volatile for cross-thread visibility). */
        @Builder.Default
        private volatile boolean cancelled = false;

        /** cancel reason */
        private String cancelReason;

        // ══════════════════════════════════════════════════════════
        //  Result object (used by UserFeedbackNode)
        // ══════════════════════════════════════════════════════════

        /** final result DTO */
        private com.shellmind.api.dto.ReActResultDTO result;

        // ══════════════════════════════════════════════════════════
        //  Tool definitions (see ShellMind ai.ts)
        // ══════════════════════════════════════════════════════════

        /**
         * Whether to use Anthropic format (tool_call_id vs tool_use_id).
         */
        private boolean useAnthropicFormat;

        // ══════════════════════════════════════════════════════════
        //  Context memory (Phase 1: dynamic prompt building)
        // ══════════════════════════════════════════════════════════

        /** Recent executed-command records, injected into the dynamic prompt. */
        @Builder.Default
        private List<String> recentCommands = new ArrayList<>();
        
        // ══════════════════════════════════════════════════════════
        //  Intent state (Phase 3: intent recognition)
        // ══════════════════════════════════════════════════════════
        
        /** Current intent name. */
        private String currentIntent;

        /** Current intent confidence. */
        private double currentIntentConfidence;

        /** AI-call retry count for the current round. */
        @Builder.Default
        private AtomicInteger aiRetryCount = new AtomicInteger(0);

        // ══════════════════════════════════════════════════════════
        //  Dead-loop detection (Phase 1: diminishing returns)
        // ══════════════════════════════════════════════════════════

        /** Per-round tool-call signatures, used for dead-loop detection. */
        @Builder.Default
        private List<String> roundSignatures = new ArrayList<>();

        /** Dead-loop threshold: stop after N consecutive rounds with the same signature. */
        @Builder.Default
        private int diminishingReturnsThreshold = 2;

        // ══════════════════════════════════════════════════════════
        //  Streaming execution thread (used to interrupt)
        // ══════════════════════════════════════════════════════════

        /** Reference to the SSE streaming execution thread. */
        private Thread streamThread;

        /** Agent-loop runtime state (heartbeat thread calls touch() on this to refresh last-active time). */
        private com.shellmind.domain.agent.service.engine.LoopState loopState;

        // ══════════════════════════════════════════════════════════
        //  Helpers
        // ══════════════════════════════════════════════════════════

        public void incrementStep() {
            currentStep.incrementAndGet();
        }

        public int getStep() {
            return currentStep.get();
        }

        public void incrementTotalToolCalls() {
            totalToolCallCount.incrementAndGet();
        }

        public void incrementRoundToolCalls() {
            roundToolCallCount.incrementAndGet();
        }

        public void resetRoundToolCalls() {
            roundToolCallCount.set(0);
        }

        public void resetRoundBuffers() {
            currentToolCalls.clear();
            currentToolResults.clear();
            assistantContent.setLength(0);
            assistantContentBoundary = 0;
            assistantReasoning.setLength(0);
        }

        public int incrementAiRetryCount() {
            return aiRetryCount.incrementAndGet();
        }

        public void resetAiRetryCount() {
            aiRetryCount.set(0);
        }

        // ══════════════════════════════════════════════════════════
        //  Cancel-state management
        // ══════════════════════════════════════════════════════════

        /**
         * Mark the task as cancelled.
         */
        public synchronized void markCancelled(String reason) {
            if (!this.cancelled) {
                this.cancelled = true;
                this.cancelReason = reason;
                if (this.stopReason == null) {
                    this.stopReason = "user_stop";
                }
                if (this.loopState != null) {
                    this.loopState.markCancelled(reason);
                }
                log.info("Task cancelled: reason={}", reason);
                interruptStreamThread();
            }
        }

        /**
         * Whether the task has been cancelled.
         */
        public boolean isCancelled() {
            return cancelled;
        }

        /**
         * Set the streaming execution thread reference.
         */
        public void setStreamThread(Thread streamThread) {
            this.streamThread = streamThread;
        }

        /**
         * Interrupt the streaming execution thread.
         */
        public void interruptStreamThread() {
            if (streamThread != null && streamThread.isAlive()) {
                log.info("Interrupting stream execution thread: threadName={}", streamThread.getName());
                streamThread.interrupt();
            }
        }

        public void appendMessage(Map<String, Object> message) {
            messageHistory.add(message);
        }

        public void appendUserMessage(String content) {
            appendMessage(Map.of("role", "user", "content", content));
        }

        public void appendAssistantMessage(String content) {
            appendMessage(Map.of("role", "assistant", "content", content));
        }

        public void appendToolMessage(String toolCallId, String content) {
            Map<String, Object> msg = useAnthropicFormat
                    ? Map.of("type", "tool_result", "tool_use_id", toolCallId, "content", content)
                    : Map.of("role", "tool", "tool_call_id", toolCallId, "content", content);
            messageHistory.add(msg);
        }

        /**
         * Append a "user confirmation" placeholder message when a tool needs interaction.
         */
        public void appendUserConfirmationMessage(String question) {
            String content = "User interaction required: " + question;
            appendMessage(Map.of("role", "user", "content", content));
        }

        public void addRecentCommand(String command) {
            if (command == null || command.trim().isEmpty()) return;
            recentCommands.add(command.trim());
            while (recentCommands.size() > 20) {
                recentCommands.remove(0);
            }
        }

        public int getOverflowToolCount() {
            return overflowToolCount;
        }

        public void setOverflowToolCount(int overflowToolCount) {
            this.overflowToolCount = overflowToolCount;
        }

        public boolean isReadOnlyExecution() {
            return readOnlyExecution;
        }

        public void setReadOnlyExecution(boolean readOnlyExecution) {
            this.readOnlyExecution = readOnlyExecution;
        }

        /**
         * Add a round signature for dead-loop detection.
         */
        public void addRoundSignature(String signature) {
            if (signature != null && !signature.isBlank()) {
                roundSignatures.add(signature);
            }
        }

        public List<String> getRoundSignatures() {
            return roundSignatures;
        }

        /**
         * Detect diminishing returns (a dead loop): N consecutive rounds with identical tool signatures.
         */
        public boolean isDiminishingReturns() {
            int threshold = diminishingReturnsThreshold;
            if (roundSignatures.size() < threshold) return false;

            int size = roundSignatures.size();
            String latest = roundSignatures.get(size - 1);
            for (int i = size - 2; i >= size - threshold; i--) {
                if (!roundSignatures.get(i).equals(latest)) {
                    return false;
                }
            }
            return true;
        }

    }

}
