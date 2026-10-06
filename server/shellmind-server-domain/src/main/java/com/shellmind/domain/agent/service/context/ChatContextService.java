package com.shellmind.domain.agent.service.context;

import com.shellmind.domain.conversation.model.valobj.MilestoneVO;
import com.shellmind.domain.agent.model.valobj.prompt.PromptContextVO;
import com.shellmind.domain.agent.service.IChatContextService;
import com.shellmind.domain.agent.service.context.provider.ContextProvider;
import com.shellmind.domain.agent.service.context.reducer.HybridReducer;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.shellmind.domain.agent.service.context.provider.ToolResultProvider;

@Service
public class ChatContextService implements IChatContextService {
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    @Resource
    private List<ContextProvider> providers;
    
    @Resource
    private HybridReducer hybridReducer;
    
    @Resource
    private ToolResultProvider toolResultProvider;

    @PostConstruct
    public void init() {
        providers.sort(Comparator.comparingInt(ContextProvider::getOrder));
    }

    @Override
    @SuppressWarnings("unchecked")
    public PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> finalCtx = new HashMap<>();

        for (ContextProvider provider : providers) {
            if (!provider.enabled()) continue;
            Map<String, Object> ctx = provider.provide(sessionId, userId, terminalSessionId, messageHistory);
            if (ctx != null) {
                finalCtx.putAll(ctx);
            }
        }

        return PromptContextVO.builder()
                .osInfo((String) finalCtx.get("osInfo"))
                .currentUser((String) finalCtx.get("currentUser"))
                .currentDirectory((String) finalCtx.get("currentDirectory"))
                .projectName((String) finalCtx.get("projectName"))
                .projectRootPath((String) finalCtx.get("projectRootPath"))
                .serverInfo((String) finalCtx.get("serverInfo"))
                .milestoneVOS((List<MilestoneVO>) finalCtx.get("milestoneVOS"))
                .toolResultSummary((String) finalCtx.get("toolResultSummary"))
                .taskDescription((String) finalCtx.get("taskDescription"))
                .priorUserInstructions((String) finalCtx.get("priorUserInstructions"))
                .sshConnectionName((String) finalCtx.get("sshConnectionName"))
                .sshHost((String) finalCtx.get("sshHost"))
                .sshPort(finalCtx.get("sshPort") != null ? (Integer) finalCtx.get("sshPort") : null)
                .sshUsername((String) finalCtx.get("sshUsername"))
                .sshConnectionId((String) finalCtx.get("sshConnectionId"))
                .coreMemories((String) finalCtx.get("coreMemories"))
                .longTermMemorySummary((String) finalCtx.get("longTermMemorySummary"))
                .build();
    }

    @Override
    public List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        return hybridReducer.reduce(history, tokenBudget > 0 ? tokenBudget : DEFAULT_MAX_CONTEXT_TOKENS);
    }
    
    @Override
    public void pushToolResult(String sessionId, String toolName, String result) {
        toolResultProvider.pushResult(sessionId, toolName, result);
    }
}
