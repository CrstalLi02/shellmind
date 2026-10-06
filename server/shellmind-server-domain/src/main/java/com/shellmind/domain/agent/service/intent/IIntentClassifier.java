package com.shellmind.domain.agent.service.intent;

import com.shellmind.domain.agent.model.valobj.intent.ConversationContextVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;

import java.util.List;

public interface IIntentClassifier {
    IntentResultVO classify(String message, ConversationContextVO context);

    /**
     * Classify with a specified model: prefer the user-configured model (modelId);
     * when unset, the implementation falls back to the default model.
     */
    IntentResultVO classify(String message, ConversationContextVO context, Long modelId);

    /**
     * Classify with recent conversation content, so the LLM classifier can resolve coreference.
     * recentUserMessages is this session's recent user messages (excluding the current one, in time order); may be null.
     */
    IntentResultVO classify(String message, ConversationContextVO context, Long modelId,
                            List<String> recentUserMessages);
}
