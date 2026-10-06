package com.shellmind.domain.llm.adapter.port;

import com.shellmind.domain.llm.model.valobj.LlmRequest;

import java.util.Optional;

/**
 * Single-turn LLM port (system prompt + user prompt → text).
 * <p>
 * Used by domain services for side-channel reasoning such as intent classification, task breakdown,
 * sub-task planning, risk classification, and summary compression.
 * Multi-turn tool calling for agents is handled by the agent runtime, not this port.
 */
public interface LlmClient {

    /**
     * Call the model.
     *
     * @return model output text; empty if the target model is not configured or unavailable (caller degrades)
     * @throws RuntimeException if the call fails (network, auth, timeout, etc.)
     */
    Optional<String> complete(LlmRequest request);

    /**
     * Clear cached clients after a user model config change.
     *
     * @param modelConfigId changed model-config ID; null clears all
     */
    void invalidateUserModel(Long modelConfigId);
}
