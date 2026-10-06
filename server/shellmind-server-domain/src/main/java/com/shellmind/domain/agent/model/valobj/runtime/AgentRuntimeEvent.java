package com.shellmind.domain.agent.model.valobj.runtime;

import java.util.List;

/**
 * One event produced by the runtime (a model output fragment or a tool-execution receipt).
 *
 * @param text              plain text in this event (may be empty)
 * @param displayText       full readable representation of this event (including a text description of function calls/results), used for non-streaming summaries
 * @param functionCallCount number of tool calls the model initiated in this event
 * @param toolResponses     receipts of completed tool executions in this event
 */
public record AgentRuntimeEvent(String text, String displayText, int functionCallCount,
                                List<ToolResponse> toolResponses) {

    public AgentRuntimeEvent {
        text = text == null ? "" : text;
        displayText = displayText == null ? "" : displayText;
        toolResponses = toolResponses == null ? List.of() : List.copyOf(toolResponses);
    }

    public boolean hasFunctionCalls() {
        return functionCallCount > 0;
    }
}
