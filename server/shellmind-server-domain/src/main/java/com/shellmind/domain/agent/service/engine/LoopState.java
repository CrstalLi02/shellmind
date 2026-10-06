package com.shellmind.domain.agent.service.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent-loop runtime state.
 * <p>Aligned with local variables in ShellMind streamingAgent.ts
 * <p>Each agent loop has an independent, thread-safe LoopState
 *
 * @author ShellMind Teaching Edition
 * 2026/6/22
 */
@Slf4j
@Data
public class LoopState {

    private final AgentLoopConfig config;

    /** Run ID */
    private String runId;

    /** Session ID */
    private String sessionId;

    /** User ID */
    private String userId;

    /** Agent ID */
    private String agentId;

    /** Start time */
    private volatile long startedAt = System.currentTimeMillis();

    /** End time */
    private volatile long finishedAt;

    /** Current round (starts at 0) */
    private int round = 0;

    /** Total tool-call count */
    private final AtomicInteger totalToolCalls = new AtomicInteger(0);

    /** Estimated total tokens */
    private volatile long totalTokens = 0;

    /** Stop reason */
    private volatile String stopReason;

    /** Whether the previous AI response was truncated by max_tokens */
    private volatile boolean wasTruncated;

    /** Last activity timestamp (used for idle-timeout detection) */
    private volatile long lastActivityTime;

    /** Loop-detection: tool-call signature per round */
    private final List<String> roundSignatures = new ArrayList<>();

    /** Message history (shared reference with DynamicContext.messageHistory) */
    private List<Map<String, Object>> messageHistory;

    /** Whether context has already been compressed (prevents infinite compression) */
    private volatile boolean contextCompressed = false;

    /** Consecutive 413 error count */
    private final AtomicInteger consecutive413Errors = new AtomicInteger(0);

    /** External control: cancel flag */
    private volatile boolean cancelled = false;

    /** Cancel reason */
    private volatile String cancelReason;

    public LoopState(AgentLoopConfig config) {
        this.config = config;
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * Initialize.
     */
    public static LoopState init(AgentLoopConfig config) {
        return new LoopState(config);
    }

    /**
     * Whether the loop should continue.
     */
    public boolean shouldContinue() {
        if (cancelled) return false;
        if (stopReason != null) return false;
        return round < config.getMaxRounds();
    }

    public void incrementRound() {
        round++;
        touch();
    }

    public void decrementRound() {
        if (round > 0) round--;
    }

    public void incrementToolCalls() {
        totalToolCalls.incrementAndGet();
        touch();
    }

    public int getTotalToolCalls() {
        return totalToolCalls.get();
    }

    public void touch() {
        lastActivityTime = System.currentTimeMillis();
    }

    public boolean isIdle() {
        return System.currentTimeMillis() - lastActivityTime > config.getIdleTimeoutMs();
    }

    public void markCancelled(String reason) {
        this.cancelled = true;
        this.cancelReason = reason;
        this.stopReason = "user_stop";
        this.finishedAt = System.currentTimeMillis();
        log.info("Agent loop cancelled: reason={}", reason);
    }

    public void setStopReason(String reason) {
        this.stopReason = reason;
        log.info("Agent loop stopped: reason={}", reason);
    }

    /**
     * Add a round signature (used for loop detection).
     */
    public void addRoundSignature(String signature) {
        roundSignatures.add(signature);
    }

    /**
     * Detect diminishing returns (a loop).
     * N consecutive rounds have identical tool signatures.
     */
    public boolean isDiminishingReturns() {
        int threshold = config.getDiminishingReturnsThreshold();
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

    /**
     * Check the token budget.
     */
    public boolean isTokenBudgetExceeded() {
        return totalTokens > config.getMaxTokenBudget();
    }

    /**
     * Estimate tokens in the message history.
     */
    public static long estimateTokens(List<Map<String, Object>> messages) {
        return TokenEstimator.estimateMessages(messages);
    }

    /**
     * Refresh the token estimate.
     */
    public void refreshTokenEstimate() {
        this.totalTokens = estimateTokens(messageHistory);
    }

    public void markCompleted() {
        this.finishedAt = System.currentTimeMillis();
    }

    public void markFailed() {
        this.finishedAt = System.currentTimeMillis();
    }
}
