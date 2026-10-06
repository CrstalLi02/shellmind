package com.shellmind.infrastructure.agent.tool;

import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.shared.model.RunContext;
import org.springframework.stereotype.Component;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SSH tool visibility filter (ADK beforeModelCallback).
 *
 * <p>Background: agents are assembled once with both local tools and remote SSH tools, then cached
 * as singletons, so the tool list cannot be trimmed per session. When the user has no SSH connection,
 * the model still "sees" remote tools such as executeCommand/readFile/writeFile. Using them in a
 * purely local coding session returns an "no SSH terminal session bound" error, and historically
 * that burned dozens of retry steps.
 *
 * <p>Approach: before each LLM request, check whether the current run (looked up by session in the
 * run registry) has an SSH terminal bound. If not, strip every remote SSH tool from the request's
 * tool declarations so the model does not know they exist. Once the user opens an SSH terminal,
 * the tools become visible again automatically.
 *
 * <p>Note: this callback only affects which tool declarations the model can see, not execution of
 * functionCalls already issued. If the model hallucinates a hidden tool from history, the tool's
 * own error response is the fallback.
 *
 * @author shellmind dev
 */
@Slf4j
@Component
public class SshTerminalToolFilter implements Callbacks.BeforeModelCallback {

    /**
     * Remote tools that require an SSH terminal session (same set registered in AgentNode).
     */
    public static final Set<String> SSH_TOOLS = Set.of(
            "executeCommand",
            "readFile", "writeFile", "listFiles", "searchInFiles", "createFile", "deleteFile"
    );

    private final AgentRunRegistry runRegistry;

    public SshTerminalToolFilter(AgentRunRegistry runRegistry) {
        this.runRegistry = runRegistry;
    }

    @Override
    public Maybe<LlmResponse> call(CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder) {
        String chatSessionId = callbackContext.sessionId();
        boolean hasSsh = runRegistry.context(chatSessionId).map(RunContext::hasTerminal).orElse(false);

        if (hasSsh) {
            return Maybe.empty(); // SSH session is bound; all tools stay visible
        }

        // Builder.tools() is package-private; read via the instance from build() (LlmRequest.tools() is public)
        Map<String, BaseTool> tools;
        try {
            tools = llmRequestBuilder.build().tools();
        } catch (Exception e) {
            log.warn("[SshToolFilter] Failed to read LlmRequest tool list, skipping filter: {}", e.getMessage());
            return Maybe.empty();
        }
        if (tools == null || tools.isEmpty()) {
            return Maybe.empty();
        }

        Map<String, BaseTool> filtered = new LinkedHashMap<>();
        int removed = 0;
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            if (entry.getValue() != null && SSH_TOOLS.contains(entry.getValue().name())) {
                removed++;
                continue;
            }
            filtered.put(entry.getKey(), entry.getValue());
        }

        if (removed > 0) {
            llmRequestBuilder.tools(filtered);
            log.info("[SshToolFilter] Session has no SSH terminal bound; hiding {} remote tools: session={}, {} tools remaining",
                    removed, chatSessionId, filtered.size());
        }

        return Maybe.empty();
    }
}
