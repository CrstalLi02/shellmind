package com.shellmind.domain.agent.service.subagent;

import com.shellmind.domain.agent.adapter.port.AgentRuntime;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRunRequest;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRuntimeEvent;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.shared.model.RunContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sub-agent manager (aligned with ShellMind agentService.ts + executeSubAgent).
 * <p>
 * Three built-in sub-agent types:
 * - Explore: read-only exploration (search/read code, do not modify files)
 * - Verification: verification agent (inspect implementation; may run commands but not modify files)
 * - General: general-purpose agent (full tool access)
 * <p>
 * Core constraints:
 * - At most 3 rounds (MAX_ROUNDS=3) to prevent infinite loops
 * - Independent child session: run context is derived from the parent (workspace, terminal, identity) and does not pollute the parent conversation
 * - Read-only agents (Explore / Verification) run the child session read-only; writes and side-effect commands are blocked by policy
 * - Results are returned to the parent conversation as a tool result
 *
 * @author ShellMind Teaching Edition
 * 2026/6/22
 */
@Slf4j
@Component
public class SubAgentManager {

    @Resource
    private AgentRuntime agentRuntime;

    @Resource
    private AgentRunRegistry runRegistry;

    /** Maximum execution rounds */
    private static final int MAX_ROUNDS = 3;

    /** Maximum sub-agent duration (milliseconds) */
    private static final long MAX_DURATION_MS = 120_000L;

    /** Running sub-agents */
    private final Map<String, RunningAgent> runningAgents = new ConcurrentHashMap<>();

    /** Sub-agent ID counter (dynamic-plan dispatch starts sub-agents concurrently, so increment must be atomic) */
    private final AtomicInteger agentIdCounter = new AtomicInteger();

    // ═══════════════════════════════════════════════════════════════
    //  Built-in agent definitions
    // ═══════════════════════════════════════════════════════════════

    public enum AgentType {
        EXPLORE("Read-only code exploration agent"),
        VERIFICATION("Verification agent that checks whether the implementation meets requirements"),
        GENERAL("General-purpose agent with full tool access");

        private final String description;

        AgentType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AgentDefinition {
        private AgentType agentType;
        private String whenToUse;
        private String description;
        private List<String> allowedTools;
        private List<String> disallowedTools;
        private boolean readOnly;
    }

    /** Built-in agent definitions */
    private static final List<AgentDefinition> BUILT_IN_AGENTS = List.of(
            AgentDefinition.builder()
                    .agentType(AgentType.EXPLORE)
                    .whenToUse("Explore the codebase, search files, read code, and understand architecture without modifying any files")
                    .description("Read-only code exploration agent")
                    .allowedTools(List.of("readFile", "listFiles", "searchFiles", "searchCode",
                            "GlobTool", "GrepTool", "WebFetchTool", "WebSearchTool"))
                    .disallowedTools(List.of("writeFile", "FileEditTool", "deleteFile",
                            "executeCommand", "executeLocalCommand", "ssh_write_file", "ssh_edit_file"))
                    .readOnly(true)
                    .build(),
            AgentDefinition.builder()
                    .agentType(AgentType.VERIFICATION)
                    .whenToUse("Verify that the implementation is correct, run adversarial tests, and check edge cases")
                    .description("Verification agent that checks whether the implementation meets requirements")
                    .allowedTools(List.of("readFile", "listFiles", "searchFiles", "searchCode",
                            "GlobTool", "GrepTool", "executeLocalCommand"))
                    .disallowedTools(List.of("writeFile", "FileEditTool", "deleteFile"))
                    .readOnly(true)
                    .build(),
            AgentDefinition.builder()
                    .agentType(AgentType.GENERAL)
                    .whenToUse("General-purpose task agent that can read/write files and run commands, for complex multi-step tasks")
                    .description("General-purpose agent with full tool access")
                    .allowedTools(List.of()) // empty list means all tools are available
                    .disallowedTools(List.of())
                    .readOnly(false)
                    .build()
    );

    // ═══════════════════════════════════════════════════════════════
    //  Running-agent management
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RunningAgent {
        private String id;
        private AgentType agentType;
        private String name;
        private long startTime;
        private volatile boolean aborted;
    }

    /**
     * Start a sub-agent.
     *
     * @param parent     parent run context (the sub-agent reuses the parent agent's runtime and runs in a derived child session)
     * @param agentType  agent type
     * @param prompt     task description
     * @param name       agent name (optional)
     * @return execution result
     */
    public AgentResult execute(RunContext parent, AgentType agentType, String prompt, String name) {
        long startTime = System.currentTimeMillis();

        // 1. Look up the agent definition
        AgentDefinition agentDef = BUILT_IN_AGENTS.stream()
                .filter(a -> a.getAgentType() == agentType)
                .findFirst()
                .orElse(null);
        if (agentDef == null) {
            return AgentResult.failure(agentType, name, "Agent definition not found: " + agentType, 0);
        }

        // 2. Generate sub-agent ID and sessionId
        String subAgentId = generateAgentId();
        String subSessionId = parent.sessionId() + "_sub_" + subAgentId;
        String agentName = name != null ? name : agentType.name().toLowerCase();

        RunningAgent running = RunningAgent.builder()
                .id(subAgentId)
                .agentType(agentType)
                .name(agentName)
                .startTime(startTime)
                .aborted(false)
                .build();
        runningAgents.put(subAgentId, running);

        log.info("Sub-agent started: id={}, type={}, name={}, session={}", subAgentId, agentType, agentName, subSessionId);

        // 3. Register child-session run context: read-only agents are forced read-only
        RunContext child = parent.forChildSession(subSessionId)
                .withReadOnly(parent.readOnly() || agentDef.isReadOnly());
        runRegistry.registerChild(child);

        try {
            if (!agentRuntime.isRegistered(parent.agentId())) {
                return AgentResult.failure(agentType, agentName, "Parent agent is not registered: " + parent.agentId(),
                        System.currentTimeMillis() - startTime);
            }

            // 4. Build the sub-agent system prompt
            String systemPrompt = buildAgentSystemPrompt(agentDef);

            // 5. Build the initial message
            String initialMessage = systemPrompt + "\n\n## Task\n" + prompt;
            AgentRunRequest request = AgentRunRequest.text(
                    parent.agentId(), parent.userId(), subSessionId, initialMessage, true);

            // 6. Run the sub-agent loop (at most MAX_ROUNDS rounds)
            StringBuilder resultBuilder = new StringBuilder();
            int totalToolCalls = 0;
            int round;

            for (round = 0; round < MAX_ROUNDS; round++) {
                // Timeout check
                if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                    log.warn("Sub-agent timed out: id={}, rounds={}", subAgentId, round);
                    resultBuilder.append("(Agent timed out)");
                    break;
                }

                // Cancel check
                if (running.isAborted()) {
                    log.info("Sub-agent cancelled: id={}", subAgentId);
                    resultBuilder.append("(Agent cancelled)");
                    break;
                }

                // Call the agent runtime
                String roundText = "";
                int roundToolCalls = 0;

                try {
                    Iterator<AgentRuntimeEvent> events = agentRuntime.run(request);

                    while (events.hasNext()) {
                        if (running.isAborted()) break;

                        AgentRuntimeEvent event = events.next();
                        String text = event.displayText();
                        if (!text.isBlank()) {
                            roundText += text;
                        }

                        // Detect tool calls
                        roundToolCalls += event.functionCallCount();
                    }
                } catch (Exception e) {
                    log.error("Sub-agent runtime call failed: round={}, error={}", round, e.getMessage());
                    if (round == 0) {
                        return AgentResult.failure(agentType, agentName,
                                "ADK Runner error: " + e.getMessage(),
                                System.currentTimeMillis() - startTime);
                    }
                    break;
                }

                totalToolCalls += roundToolCalls;

                // No tool calls → return text
                if (roundToolCalls == 0) {
                    resultBuilder.append(roundText.isEmpty() ? "(no output)" : roundText);
                    break;
                }

                // Tool calls happened → record text and continue to the next round
                if (!roundText.isEmpty()) {
                    resultBuilder.append(roundText).append("\n");
                }

                // Last round still has tool calls
                if (round == MAX_ROUNDS - 1) {
                    resultBuilder.append("(Agent reached the maximum round limit)");
                    log.warn("Sub-agent reached max rounds: id={}, maxRounds={}", subAgentId, MAX_ROUNDS);
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;
            String output = resultBuilder.toString().trim();

            log.info("Sub-agent finished: id={}, type={}, rounds={}, toolCalls={}, durationMs={}, outputLen={}",
                    subAgentId, agentType, round + 1, totalToolCalls, durationMs, output.length());

            return AgentResult.success(agentType, agentName, output, durationMs, totalToolCalls, round + 1);

        } catch (Exception e) {
            log.error("Sub-agent execution exception: id={}, error={}", subAgentId, e.getMessage(), e);
            return AgentResult.failure(agentType, agentName,
                    "Agent error: " + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        } finally {
            runningAgents.remove(subAgentId);
            runRegistry.unregister(subSessionId);
        }
    }

    /**
     * Cancel a sub-agent.
     */
    public boolean abort(String agentId) {
        RunningAgent agent = runningAgents.get(agentId);
        if (agent != null) {
            agent.setAborted(true);
            log.info("Sub-agent cancel requested: id={}", agentId);
            return true;
        }
        return false;
    }

    /**
     * List running sub-agents.
     */
    public List<RunningAgent> getRunningAgents() {
        return new ArrayList<>(runningAgents.values());
    }

    /**
     * List built-in agent definitions.
     */
    public List<AgentDefinition> getBuiltInAgents() {
        return BUILT_IN_AGENTS;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String generateAgentId() {
        return "agent_" + System.currentTimeMillis() + "_" + agentIdCounter.incrementAndGet();
    }

    /**
     * Build the sub-agent system prompt.
     */
    private String buildAgentSystemPrompt(AgentDefinition agentDef) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ShellMind's ").append(agentDef.getDescription()).append(". You are executing a sub-task.\n\n");
        sb.append("## Conduct\n");
        sb.append("- Stay focused on the assigned task; do not drift\n");
        sb.append("- Keep results concise and structured so the parent conversation can integrate them\n");
        sb.append("- If the task cannot be completed, state the reason clearly\n");
        sb.append("- Do not repeat background already known in the parent conversation\n");

        if (agentDef.isReadOnly()) {
            sb.append("\n## Read-only constraint\n");
            sb.append("You are a read-only agent and cannot modify any project files. ");
            sb.append("If the task requires file changes, report this constraint and describe the changes that would be needed.\n");
        }

        sb.append("\n## Round limit\n");
        sb.append("You have at most ").append(MAX_ROUNDS).append(" interaction rounds; use them efficiently.\n");

        return sb.toString();
    }

    /**
     * Format the agent list as a description for the AI prompt.
     */
    public String formatAgentListForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (AgentDefinition agent : BUILT_IN_AGENTS) {
            String toolsDesc = agent.getAllowedTools().isEmpty()
                    ? "all tools"
                    : String.join(", ", agent.getAllowedTools());
            sb.append("- ").append(agent.getAgentType().name())
                    .append(": ").append(agent.getWhenToUse())
                    .append(" (Tools: ").append(toolsDesc).append(")\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Result data structure
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AgentResult {
        private AgentType agentType;
        private String name;
        private boolean success;
        private String output;
        private long durationMs;
        private int totalToolCalls;
        private int roundsUsed;

        public static AgentResult success(AgentType type, String name, String output,
                                           long durationMs, int toolCalls, int rounds) {
            return AgentResult.builder()
                    .agentType(type)
                    .name(name)
                    .success(true)
                    .output(output)
                    .durationMs(durationMs)
                    .totalToolCalls(toolCalls)
                    .roundsUsed(rounds)
                    .build();
        }

        public static AgentResult failure(AgentType type, String name, String error, long durationMs) {
            return AgentResult.builder()
                    .agentType(type)
                    .name(name)
                    .success(false)
                    .output(error)
                    .durationMs(durationMs)
                    .totalToolCalls(0)
                    .roundsUsed(0)
                    .build();
        }

        /**
         * Format as a tool result returned to the parent conversation.
         */
        public String toToolResult() {
            return String.format("[Agent: %s (%s)] %s in %dms, %d tool calls, %d rounds\n\n%s",
                    name, agentType, success ? "completed" : "failed",
                    durationMs, totalToolCalls, roundsUsed, output);
        }
    }
}
