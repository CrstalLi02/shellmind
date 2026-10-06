package com.shellmind.cases;

import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

/**
 * AI agent ReAct execution API
 *
 * <p>Core capabilities:
 * - Multi-turn conversation loop (ReAct)
 * - Tool calling
 * - Streaming output (SSE)
 * - Step control and stop-condition checks
 *
 * <p>ReAct loop:
 * 1. Build the user message and append it to history
 * 2. Call the AI (with tool definitions)
 * 3. Parse the AI response:
 *    - If it contains tool_calls → run tools → append results → go back to step 1
 *    - If it does not contain tool_calls → return the result to the user
 * 4. Stop conditions: finish / max steps reached / error
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4 13:56
 */
public interface IAIAgentReActServiceCase {

    /**
     * Streaming chat (ReAct mode)
     *
     * @param requestDTO chat request
     * @return SSE event emitter
     */
    void chatStream(ChatRequestDTO requestDTO, ClientChannel channel);

    /**
     * Plain chat (single-turn, non-streaming)
     *
     * @param requestDTO chat request
     * @return chat response content
     */
    String chat(ChatRequestDTO requestDTO);

}
