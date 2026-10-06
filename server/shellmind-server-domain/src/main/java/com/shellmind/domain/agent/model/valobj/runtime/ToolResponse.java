package com.shellmind.domain.agent.model.valobj.runtime;

import java.util.Map;

/**
 * Receipt of one tool execution.
 *
 * @param toolCallId tool-call ID (null when the runtime did not provide one)
 * @param toolName   tool name
 * @param response   structured result returned by the tool; null when the runtime returned an empty result
 */
public record ToolResponse(String toolCallId, String toolName, Map<String, Object> response) {
}
