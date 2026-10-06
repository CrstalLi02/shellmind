package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.llm.adapter.port.LlmClient;
import com.shellmind.domain.llm.model.valobj.LlmRequest;
import com.shellmind.domain.llm.model.valobj.LlmTarget;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Planner-agent builder — translates a user task into a structured multi-agent execution plan.
 * <p>
 * Reuses the parent agent's own model (same model-channel config) and constrains a single-turn
 * call so the model outputs only a JSON task plan (no Markdown or other content).
 * The output is parsed by PlanParser, validated by PlanValidator, then executed.
 * <p>
 * Optional sub-agents are SubAgentManager's built-in types: EXPLORE (read-only exploration),
 * VERIFICATION (may run commands but not change files), GENERAL (full tool access).
 */
@Slf4j
@Service
public class PlannerAgentBuilder {

    /** Planner single-call timeout */
    private static final long PLAN_TIMEOUT_SECONDS = 60;

    /**
     * Planner system instruction:
     * constrain output to a pure JSON plan; do not split simple tasks; complex tasks at most 5 parallel
     * read-only tasks; write operations (GENERAL) must depend on explore/verify tasks; choose only from
     * allowed sub-agent types.
     */
    private static final String INSTRUCTION = """
            You are a multi-agent task planner. Output only one JSON object; do not output Markdown or other text.
            JSON format:
            {"tasks":[{"taskId":"unique ID","agentName":"allowed sub-agent type","request":"complete task instruction","dependsOn":["dependency task ID"]}],"maxConcurrency":4}
            Sub-agent types:
            - EXPLORE: read-only exploration; search/read code or inspect server status; do not modify any files
            - VERIFICATION: verification checks; may run compile/test/diagnose commands, but do not modify files
            - GENERAL: general execution; may read/write files and run change commands
            Rules:
            1. Simple tasks output only one task. Complex tasks may have at most 5 parallel read-only tasks (EXPLORE/VERIFICATION).
            2. GENERAL change tasks must depend on related EXPLORE or VERIFICATION tasks.
            3. Each request must be a complete, independently executable instruction; the sub-agent cannot see parent-conversation context.
            4. Choose only from the allowed sub-agent types; do not invent new types.
            """;

    @Resource
    private LlmClient llmClient;

    /**
     * Run task planning.
     *
     * @param agentId       parent agent ID (reuse its ChatModel)
     * @param userRequest   original user task description
     * @param allowedAgents allowed sub-agent types, also injected into the instruction and prompt
     * @return planner JSON plan text (parsing is left to PlanParser)
     * @throws IllegalStateException parent agent is not registered, or planning failed/timed out
     */
    public String plan(String agentId, String userRequest, List<String> allowedAgents) {
        String allowed = String.join(",", allowedAgents);
        LlmRequest request = LlmRequest.of(LlmTarget.agent(agentId),
                INSTRUCTION + "\nAllowed agents:" + allowed,
                "User task:" + userRequest + "\nAllowed agents:" + allowed);

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> llmClient.complete(request)
                .orElseThrow(() -> new IllegalStateException("planner chat model not available for agent: " + agentId)));
        try {
            String content = future.get(PLAN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Dynamic planning complete | agentId={} | planLen={}", agentId, content != null ? content.length() : 0);
            return content != null ? content : "";
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IllegalStateException("planner timeout after " + PLAN_TIMEOUT_SECONDS + "s", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("planner interrupted", interrupted);
        } catch (ExecutionException execution) {
            if (execution.getCause() instanceof IllegalStateException unavailable) {
                throw unavailable;
            }
            throw new IllegalStateException("planner failed", execution.getCause());
        } catch (Exception exception) {
            throw new IllegalStateException("planner failed", exception);
        }
    }

}
