package com.shellmind.domain.memory.service;

import com.shellmind.domain.memory.model.entity.LongTermMemoryEntity;

import java.util.List;

/**
 * Long-term memory service interface.
 * <p>
 * Defines automatic extraction of structured memories from the conversation stream,
 * recall of related memories, and building of prompt summaries.
 * Implemented by {@code LongTermMemoryService} and invoked at each ReAct-loop stage:
 * <ul>
 *   <li>{@link AiCallNode} calls {@link #recordUserMessage} on the first turn to extract user preferences</li>
 *   <li>{@link ToolCallNode} calls {@link #recordToolObservation} to extract environment/software/failure signals</li>
 *   <li>{@link AiCallNode} calls {@link #recordAssistantConclusion} to extract troubleshooting conclusions</li>
 *   <li>{@link LongTermMemoryProvider} calls {@link #buildMemorySummary} to build the summary injected into the prompt</li>
 * </ul>
 *
 * @see com.shellmind.domain.memory.service.LongTermMemoryService
 */
public interface ILongTermMemoryService {

    /**
     * Record a user message and extract a user-preference memory (USER_PREFERENCE) when applicable.
     * <p>
     * When the message contains preference hints such as "from now on", "default", or "remember",
     * it is stored as a preference. Intent labels are appended to keywords to improve later recall.
     *
     * @param userId      user ID
     * @param sessionId   session ID
     * @param message     original user message
     * @param intentLabel current intent label (may be null)
     */
    void recordUserMessage(String userId, String sessionId, String message, String intentLabel);

    /**
     * Record a tool result and automatically extract environment facts, software versions, and failure signals.
     * <p>
     * Regex matching on tool output extracts:
     * <ul>
     *   <li>Operating system (ENVIRONMENT_FACT)</li>
     *   <li>Software version (SOFTWARE_FACT; generic regex + Redis-specific regex)</li>
     *   <li>Failure signal (TROUBLESHOOTING_CASE, only when success=false)</li>
     * </ul>
     *
     * @param userId        user ID
     * @param sessionId     session ID
     * @param toolName      tool name
     * @param resultContent tool execution result
     * @param success       whether the tool succeeded
     */
    void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success);

    /**
     * Record an assistant reply and extract a troubleshooting-case memory (TROUBLESHOOTING_CASE)
     * when it contains keywords such as "conclusion", "cause", or "recommend".
     *
     * @param userId           user ID
     * @param sessionId        session ID
     * @param assistantContent assistant reply content
     */
    void recordAssistantConclusion(String userId, String sessionId, String assistantContent);

    /**
     * Query long-term memories related to the current conversation (coarse filter + fine ranking).
     * <p>
     * Stage 1: load the 120 most recent memories from the DB (coarse filter);
     * Stage 2: score overlap between query keywords and memory keywords (fine ranking), return Top-N.
     *
     * @param userId user ID
     * @param query  recall query (first message concatenated with the latest user message)
     * @param limit  max number of results
     * @return relevant memories (descending by score)
     */
    List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit);

    /**
     * Build a long-term memory summary string for the [Long-term memory] section of the prompt.
     * <p>
     * Output format example:
     * <pre>
     * - [USER_PREFERENCE] from now on always add sudo when running commands
     * - [SOFTWARE_FACT] redis version is 7.0.11
     * </pre>
     *
     * @param userId user ID
     * @param query  recall query
     * @param limit  max number of results
     * @return memory summary string, or empty string when there is no memory
     */
    String buildMemorySummary(String userId, String query, int limit);
}
