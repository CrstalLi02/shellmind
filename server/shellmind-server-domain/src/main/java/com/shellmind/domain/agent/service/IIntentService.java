package com.shellmind.domain.agent.service;

import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;

import java.util.List;

public interface IIntentService {
    IntentResultVO classify(String sessionId, String userId, String message);

    /**
     * Classify with a specified model: modelId is passed through to the LLM intent classifier, using the user-configured model.
     */
    IntentResultVO classify(String sessionId, String userId, String message, Long modelId);

    /**
     * Classify with recent conversation content: the LLM intent classifier can see this session's recent
     * user messages, so it correctly resolves execution instructions that refer to earlier turns,
     * such as "start improving" or "handle as above".
     *
     * @param recentUserMessages this session's recent user messages (excluding the current one, in time order); may be null
     */
    IntentResultVO classify(String sessionId, String userId, String message, Long modelId,
                            List<String> recentUserMessages);
}
