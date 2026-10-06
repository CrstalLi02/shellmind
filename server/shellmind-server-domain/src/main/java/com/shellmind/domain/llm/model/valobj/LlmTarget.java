package com.shellmind.domain.llm.model.valobj;

/**
 * Which model to call.
 *
 * @param kind        target kind
 * @param agentId     agent ID when {@link Kind#AGENT}
 * @param userModelId preferred user model-config ID when {@link Kind#AUXILIARY} (may be null)
 */
public record LlmTarget(Kind kind, String agentId, Long userModelId) {

    public enum Kind {
        /** Use the model attached to a fully assembled agent. */
        AGENT,
        /** Auxiliary model: prefer the user-configured model in settings; fall back to intent-ai-api if unset. */
        AUXILIARY
    }

    public static LlmTarget agent(String agentId) {
        return new LlmTarget(Kind.AGENT, agentId, null);
    }

    public static LlmTarget auxiliary(Long preferredUserModelId) {
        return new LlmTarget(Kind.AUXILIARY, null, preferredUserModelId);
    }
}
