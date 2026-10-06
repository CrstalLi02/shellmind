package com.shellmind.infrastructure.agent.armory.node;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.Runner;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * Injects event-compaction config into a Runner.
 *
 * <p>ADK 1.2 InMemoryRunner leaves event compaction off by default ({@code EventsCompactionConfig} is null),
 * so session events grow without bound in long conversations and eventually exceed the model token limit.
 *
 * <p>This helper injects {@code EventsCompactionConfig} via reflection so the Runner runs
 * {@code SlidingWindowEventCompactor} on old events after each {@code runAsync}.
 *
 * <p>Compaction policy:
 * - compactionInterval: compact after every N accumulated events
 * - overlapSize: keep the most recent N events uncompressed (overlap window)
 * - tokenThreshold: also compact when token count exceeds this threshold
 * - eventRetentionSize: keep the most recent N events after compaction
 *
 * @author shellmind-java
 * 2026/6/21
 */
@Slf4j
public class RunnerCompactionInjector {

    /** Compact after every 50 events by default */
    private static final int DEFAULT_COMPACTION_INTERVAL = 50;

    /** Keep the most recent 10 events uncompressed by default */
    private static final int DEFAULT_OVERLAP_SIZE = 10;

    /** Default token threshold (compact when over 60000) */
    private static final int DEFAULT_TOKEN_THRESHOLD = 60_000;

    /** Keep 20 events after compaction by default */
    private static final int DEFAULT_EVENT_RETENTION_SIZE = 20;

    /**
     * Inject event-compaction config into a Runner.
     *
     * <p>Requirements:
     * 1. Runner is InMemoryRunner or a subclass
     * 2. agent is an LlmAgent with a usable model
     *
     * @param runner    ADK Runner
     * @param baseAgent agent associated with the Runner
     * @return true if injection succeeded
     */
    public static boolean injectCompaction(Runner runner, BaseAgent baseAgent) {
        return injectCompaction(runner, baseAgent,
                DEFAULT_COMPACTION_INTERVAL,
                DEFAULT_OVERLAP_SIZE,
                DEFAULT_TOKEN_THRESHOLD,
                DEFAULT_EVENT_RETENTION_SIZE);
    }

    /**
     * Inject event-compaction config into a Runner (custom parameters).
     */
    public static boolean injectCompaction(Runner runner, BaseAgent baseAgent,
                                           int compactionInterval,
                                           int overlapSize,
                                           int tokenThreshold,
                                           int eventRetentionSize) {
        if (runner == null || baseAgent == null) {
            log.warn("Runner or BaseAgent is null; skipping compaction config injection");
            return false;
        }

        // Extract BaseLlm from the LlmAgent
        BaseLlm baseLlm = extractBaseLlm(baseAgent);
        if (baseLlm == null) {
            log.warn("Agent {} is not an LlmAgent or has no usable model; skipping compaction config injection", baseAgent.name());
            return false;
        }

        // Build EventsCompactionConfig
        LlmEventSummarizer summarizer = new LlmEventSummarizer(baseLlm);
        EventsCompactionConfig config = new EventsCompactionConfig(
                compactionInterval,
                overlapSize,
                summarizer,
                tokenThreshold,
                eventRetentionSize
        );

        // Inject into Runner.eventsCompactionConfig via reflection
        try {
            Field field = Runner.class.getDeclaredField("eventsCompactionConfig");
            field.setAccessible(true);
            field.set(runner, config);
            log.info("Injected Runner event-compaction config: agent={}, compactionInterval={}, overlapSize={}, tokenThreshold={}, eventRetentionSize={}",
                    baseAgent.name(), compactionInterval, overlapSize, tokenThreshold, eventRetentionSize);
            return true;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.error("Failed to inject Runner event-compaction config: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Extract a BaseLlm from a BaseAgent.
     *
     * <p>If the agent is an LlmAgent, extract directly.
     * If it is a composite such as SequentialAgent, recurse into sub-agents for the first LlmAgent with a model.
     * LlmAgent.model() returns Optional&lt;Model&gt;; Model.model() returns Optional&lt;BaseLlm&gt;.
     */
    private static BaseLlm extractBaseLlm(BaseAgent baseAgent) {
        if (baseAgent == null) {
            return null;
        }

        // If this is an LlmAgent, extract directly
        if (baseAgent instanceof LlmAgent llmAgent) {
            BaseLlm llm = extractFromLlmAgent(llmAgent);
            if (llm != null) {
                return llm;
            }
        }

        // Recurse into sub-agents
        List<? extends BaseAgent> subAgents = baseAgent.subAgents();
        if (subAgents != null) {
            for (BaseAgent sub : subAgents) {
                BaseLlm llm = extractBaseLlm(sub);
                if (llm != null) {
                    return llm;
                }
            }
        }

        return null;
    }

    /**
     * Extract a BaseLlm from an LlmAgent.
     */
    private static BaseLlm extractFromLlmAgent(LlmAgent llmAgent) {
        // Prefer model()
        Optional<com.google.adk.models.Model> modelOpt = llmAgent.model();
        if (modelOpt != null && modelOpt.isPresent()) {
            Optional<BaseLlm> llmOpt = modelOpt.get().model();
            if (llmOpt != null && llmOpt.isPresent()) {
                return llmOpt.get();
            }
        }

        // Fall back to resolvedModel()
        com.google.adk.models.Model resolvedModel = llmAgent.resolvedModel();
        if (resolvedModel != null) {
            Optional<BaseLlm> llmOpt = resolvedModel.model();
            if (llmOpt != null && llmOpt.isPresent()) {
                return llmOpt.get();
            }
        }

        return null;
    }
}
