package com.shellmind.domain.llm.model.valobj;

/**
 * Single-turn completion request.
 *
 * @param target              target model
 * @param systemPrompt        system prompt; may be null
 * @param userPrompt          user prompt
 * @param fallbackTemperature sampling temperature used only when the auxiliary model falls back to intent-ai-api;
 *                            null uses the model default. User-configured models always use their own defaults.
 */
public record LlmRequest(LlmTarget target, String systemPrompt, String userPrompt, Double fallbackTemperature) {

    public static LlmRequest of(LlmTarget target, String systemPrompt, String userPrompt) {
        return new LlmRequest(target, systemPrompt, userPrompt, null);
    }
}
