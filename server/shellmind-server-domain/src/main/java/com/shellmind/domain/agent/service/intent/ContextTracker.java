package com.shellmind.domain.agent.service.intent;

import com.shellmind.domain.agent.model.valobj.intent.ConversationContextVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentHistoryEntryVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ContextTracker {
    private static final int WINDOW_SIZE = 10;
    private final Map<String, ConversationContextVO> contexts = new ConcurrentHashMap<>();

    public ConversationContextVO getContext(String sessionId) {
        return contexts.computeIfAbsent(sessionId, id -> ConversationContextVO.builder()
            .recentIntents(new LinkedList<>())
            .turnCount(0)
            .sessionStartTime(System.currentTimeMillis())
            .build());
    }

    public void updateContext(String sessionId, IntentResultVO result) {
        ConversationContextVO ctx = getContext(sessionId);
        ctx.getRecentIntents().addLast(IntentHistoryEntryVO.builder()
            .intent(result.getIntent())
            .confidence(result.getConfidence())
            .timestamp(System.currentTimeMillis())
            .build());
        if (ctx.getRecentIntents().size() > WINDOW_SIZE) {
            ctx.getRecentIntents().removeFirst();
        }
        ctx.setTurnCount(ctx.getTurnCount() + 1);
        ctx.setLastIntent(result.getIntent());
    }

    public void clear(String sessionId) {
        contexts.remove(sessionId);
    }
}
