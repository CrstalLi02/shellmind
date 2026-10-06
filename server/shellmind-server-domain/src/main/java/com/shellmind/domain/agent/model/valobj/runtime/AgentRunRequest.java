package com.shellmind.domain.agent.model.valobj.runtime;

import java.util.List;

/**
 * Input for one run.
 *
 * @param agentId   agent to run
 * @param userId    user ID
 * @param sessionId session ID
 * @param parts     user input (text, images, etc.)
 * @param streaming whether to stream text fragments
 */
public record AgentRunRequest(String agentId, String userId, String sessionId,
                              List<InputPart> parts, boolean streaming) {

    public AgentRunRequest {
        parts = List.copyOf(parts);
    }

    public static AgentRunRequest text(String agentId, String userId, String sessionId, String text, boolean streaming) {
        return new AgentRunRequest(agentId, userId, sessionId, List.of(InputPart.text(text)), streaming);
    }
}
