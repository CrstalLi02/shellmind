package com.shellmind.domain.agent.service.intent;

import com.shellmind.domain.agent.model.valobj.intent.ConversationContextVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import com.shellmind.domain.agent.service.IIntentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class IntentService implements IIntentService {

    @Resource
    private RuleIntentClassifier ruleClassifier;

    @Resource
    private LLMIntentClassifier llmClassifier;

    @Resource
    private ContextTracker contextTracker;

    // Simple LRU cache, max 200 entries
    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > 200;
            }
        });

    private static class CacheEntry {
        IntentResultVO result;
        long expireTime;
        CacheEntry(IntentResultVO result, long expireTime) {
            this.result = result;
            this.expireTime = expireTime;
        }
    }

    @Override
    public IntentResultVO classify(String sessionId, String userId, String message) {
        return classify(sessionId, userId, message, null);
    }

    @Override
    public IntentResultVO classify(String sessionId, String userId, String message, Long modelId) {
        return classify(sessionId, userId, message, modelId, null);
    }

    /**
     * Classification strategy: LLM first (it understands natural language and multi-turn references); rules are fallback only.
     *
     * Problem with the old strategy (rules first): the rule classifier could score short phrases like
     * "start improving" above 0.8 via MONITOR keywords (any keyword hit adds score even if "start" is not
     * in the rule) and short-circuit, so the LLM never ran — the root cause of several consecutive user
     * requests not being executed correctly.
     */
    @Override
    public IntentResultVO classify(String sessionId, String userId, String message, Long modelId,
                                   List<String> recentUserMessages) {
        String cacheKey = sessionId + ":" + modelId + ":" + sha256(message);

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireTime > System.currentTimeMillis()) {
            log.debug("Intent-classification cache hit: session={}", sessionId);
            return cached.result;
        }

        ConversationContextVO context = contextTracker.getContext(sessionId);

        // Layer 1: LLM classification (primary path), with recent conversation for coreference
        IntentResultVO llmResult = llmClassifier.classify(message, context, modelId, recentUserMessages);

        IntentResultVO finalResult;
        if (llmResult.getConfidence() >= 0.5) {
            finalResult = llmResult;
        } else {
            // Layer 2: rule fallback (when the LLM is unavailable or low-confidence)
            IntentResultVO ruleResult = ruleClassifier.classify(message, context);
            finalResult = ruleResult.getConfidence() > llmResult.getConfidence() ? ruleResult : llmResult;
            if (finalResult == ruleResult) {
                log.info("Intent classification LLM low confidence ({}), using rule result: {}",
                        llmResult.getConfidence(), finalResult.getIntent());
            }
        }

        recordAndCache(sessionId, cacheKey, finalResult, context);
        return finalResult;
    }

    private void recordAndCache(String sessionId, String cacheKey, IntentResultVO result,
                                  ConversationContextVO context) {
        contextTracker.updateContext(sessionId, result);

        // [Phase 3] Record entities into context (used for coreference resolution)
        if (context != null && result.getEntities() != null) {
            result.getEntities().forEach(context::putEntity);
        }

        // Cache for 5 minutes
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis() + 5 * 60 * 1000));
    }

    /**
     * [Phase 3 fix] SHA-256 instead of hashCode, to avoid cache-key collisions
     */
    private String sha256(String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16); // first 16 hex chars are enough
        } catch (Exception e) {
            // Fallback: use hashCode
            return Integer.toHexString(message.hashCode());
        }
    }
}
