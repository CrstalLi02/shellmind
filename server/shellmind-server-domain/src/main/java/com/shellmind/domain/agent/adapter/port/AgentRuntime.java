package com.shellmind.domain.agent.adapter.port;

import com.shellmind.domain.agent.model.valobj.runtime.AgentRunRequest;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRuntimeEvent;

import java.util.Iterator;

/**
 * Agent-runtime port: how an assembled agent actually runs (model calls + tool-call loop) is up to the implementation.
 * <p>
 * Run context needed by tools is not passed through here: the caller registers {@code RunContext}
 * in {@code AgentRunRegistry} before the run, and tools look it up by session ID.
 */
public interface AgentRuntime {

    /**
     * Run one conversation turn. Iterating the returned events blocks until the runtime produces
     * the next event; iteration ending means this turn is over.
     *
     * @throws RuntimeException runtime invocation failed (the caller may retry)
     */
    Iterator<AgentRuntimeEvent> run(AgentRunRequest request);

    /**
     * Resolve the agent that will actually run: when the user selected a custom model, assemble
     * (and cache) a runtime agent for that model.
     *
     * @param modelId user model-config ID; when null, return sourceAgentId directly
     */
    String resolveAgent(String sourceAgentId, Long modelId);

    /** After the user's model config changes, drop the runtime agent assembled for that model */
    void evictModelAgent(Long modelId);

    /** Create a new session for the agent; returns the session ID */
    String createSession(String agentId, String userId);

    /** Whether the agent is already assembled */
    boolean isRegistered(String agentId);
}
