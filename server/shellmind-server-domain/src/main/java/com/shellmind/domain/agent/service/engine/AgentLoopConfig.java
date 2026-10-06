package com.shellmind.domain.agent.service.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent-loop configuration.
 * <p>Aligned with AgentLoopConfig in ShellMind streamingAgent.ts
 * <p>Built by RootNode from agent YAML config + intent tier
 *
 * @author ShellMind Teaching Edition
 * 2026/6/22
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentLoopConfig {

    /** Mode: chat (conversation) / agent (autonomous) */
    @Builder.Default
    private AgentMode mode = AgentMode.AGENT;

    /** Max loop rounds (chat=10, agent=20) */
    @Builder.Default
    private int maxRounds = 20;

    /** Max tool calls per round */
    @Builder.Default
    private int maxToolCallsPerRound = 5;

    /** Global max total tool calls */
    @Builder.Default
    private int maxTotalToolCalls = 200;

    /** AI-call retry count */
    @Builder.Default
    private int maxAiRetries = 3;

    /** Tool-execution retry count */
    @Builder.Default
    private int maxToolRetries = 3;

    /** Retry delay base (ms), exponential backoff: base * 2^attempt */
    @Builder.Default
    private long retryDelayBaseMs = 1000L;

    /** Idle timeout (ms), default 10 minutes */
    @Builder.Default
    private long idleTimeoutMs = 600_000L;

    /** Token-budget cap */
    @Builder.Default
    private int maxTokenBudget = 200_000;

    /** Auto-continue (default true in agent mode) */
    @Builder.Default
    private boolean autoContinue = true;

    /** Loop-detection threshold: stop after N consecutive rounds with the same tool signature */
    @Builder.Default
    private int diminishingReturnsThreshold = 2;

    public enum AgentMode {
        /** Conversation mode: light tool use */
        CHAT,
        /** Autonomous mode: multi-round tool use + task breakdown */
        AGENT
    }
}
