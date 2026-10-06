package com.shellmind.domain.agent.service.intent.enhancer;

import com.shellmind.domain.agent.model.valobj.intent.ConversationContextVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import com.shellmind.domain.agent.service.intent.ContextTracker;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Intent-enhancement orchestrator.
 * <p>
 * Coordinates IntentService (classification) + IntentEnhancer (context enrichment) + SignalExtractor (signal extraction).
 * <p>
 * Aligned with the full ShellMind IntentService flow:
 * 1. Rule fast path → return immediately on high confidence
 * 2. Async model pre-analysis → LLM classification
 * 3. Signal extraction → structured information
 * 4. Coreference resolution → "it"/"this" → concrete entity
 * 5. Semantic retrieval → related context
 * 6. Context tracking → maintain conversation history
 *
 * @author ShellMind Teaching Edition
 * 2026/6/22
 */
@Slf4j
@Component
public class IntentOrchestrator {

    @Resource
    private SignalExtractor signalExtractor;

    @Resource
    private IntentEnhancer intentEnhancer;

    @Resource
    private ContextTracker contextTracker;

    /**
     * Coreference resolution: replace pronouns with concrete entities.
     * <p>
     * Examples:
     * - "why did it go down" → "why did nginx go down" (from context)
     * - "this file" → "src/App.tsx this file"
     * - "try again" → repeat the last command
     */
    public String resolveCoreference(String userInput, ConversationContextVO context,
                                      SignalExtractor.ExtractedSignals signals) {
        if (context == null || userInput == null) return userInput;

        String resolved = userInput;
        String lowerInput = userInput.toLowerCase();

        // Detect pronouns
        boolean hasPronoun = lowerInput.contains("它") || lowerInput.contains("这个")
                || lowerInput.contains("那个") || lowerInput.contains("该")
                || lowerInput.matches(".*\\b(it|this|that)\\b.*");

        if (!hasPronoun) return resolved;

        log.info("Pronoun detected, starting coreference resolution: input={}", userInput);

        // Extract entities from recent context
        String lastService = context.getLastEntity("service");
        String lastFile = context.getLastEntity("file");
        String lastCommand = context.getLastEntity("command");

        // Replace pronouns (Chinese pronouns are substituted in place; English ones are only detected,
        // because rewriting every "this"/"that" in an English sentence is too lossy)
        if (lowerInput.contains("它") && lastService != null) {
            resolved = resolved.replace("它", lastService);
            log.info("Coreference resolved: 它 → {}", lastService);
        }
        if ((lowerInput.contains("这个") || lowerInput.contains("该")) && lastFile != null) {
            resolved = resolved.replace("这个", lastFile).replace("该", lastFile);
            log.info("Coreference resolved: 这个/该 → {}", lastFile);
        }
        if ((lowerInput.contains("再试一次") || lowerInput.contains("try again")) && lastCommand != null) {
            resolved = lastCommand;
            log.info("Coreference resolved: try again → {}", lastCommand);
        }

        return resolved;
    }

    /**
     * Build an enhanced intent result.
     * <p>
     * Combine intent classification + signal extraction + context enhancement into the final result.
     */
    /**
     * Build an enhanced intent result (including project-file search fallback).
     * [P2-5] Adds projectRootPath and passes it to IntentEnhancer for file search.
     */
    public EnhancedIntentResult buildEnhancedResult(IntentResultVO intentResult,
                                                     String userInput,
                                                     String sessionId,
                                                     ConversationContextVO context,
                                                     String projectRootPath) {
        if (context == null && sessionId != null && !sessionId.isBlank()) {
            context = contextTracker.getContext(sessionId);
        }

        // 1. Extract signals
        SignalExtractor.ExtractedSignals signals = signalExtractor.extract(userInput);

        // 2. Coreference resolution
        String resolvedInput = resolveCoreference(userInput, context, signals);

        // 3. If resolved input differs from the original, re-extract signals
        if (!resolvedInput.equals(userInput)) {
            signals = signalExtractor.extract(resolvedInput);
            log.info("Re-extracted signals after coreference resolution: resolved={}", resolvedInput);
        }

        // 4. Intent enhancement ([P2-5] pass projectRootPath to support fallback search)
        IntentEnhancer.EnhanceResult enhanceResult = intentEnhancer.enhance(resolvedInput, sessionId, projectRootPath);

        // 5. Merge entities
        java.util.Map<String, String> mergedEntities = new java.util.HashMap<>();
        if (intentResult.getEntities() != null) {
            mergedEntities.putAll(intentResult.getEntities());
        }
        // Supplement entities from signals
        if (!signals.getCommandHints().isEmpty()) {
            mergedEntities.putIfAbsent("service", signals.getCommandHints().get(0));
        }
        if (!signals.getFilePaths().isEmpty()) {
            mergedEntities.putIfAbsent("file", signals.getFilePaths().get(0));
        }

        return EnhancedIntentResult.builder()
                .intent(intentResult.getIntent())
                .confidence(intentResult.getConfidence())
                .entities(mergedEntities)
                .resolvedInput(resolvedInput)
                .originalInput(userInput)
                .signals(signals)
                .enhancedContext(enhanceResult.getEnhancedContext())
                .searchFallback(enhanceResult.isSearchFallback())
                .build();
    }

    /**
     * Compatibility method (without projectRootPath).
     */
    public EnhancedIntentResult buildEnhancedResult(IntentResultVO intentResult,
                                                     String userInput,
                                                     String sessionId,
                                                     ConversationContextVO context) {
        return buildEnhancedResult(intentResult, userInput, sessionId, context, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Data structures
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EnhancedIntentResult {
        private com.shellmind.domain.agent.model.valobj.intent.IntentTypeEnumVO intent;
        private double confidence;
        private java.util.Map<String, String> entities;
        private String resolvedInput;
        private String originalInput;
        private SignalExtractor.ExtractedSignals signals;
        private String enhancedContext;
        /** Whether project-file search fallback was used [P2-5] */
        private boolean searchFallback;

        public boolean hasEnhancement() {
            return enhancedContext != null && !enhancedContext.isBlank();
        }
    }
}
