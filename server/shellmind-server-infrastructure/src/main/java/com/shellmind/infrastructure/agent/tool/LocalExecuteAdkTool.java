package com.shellmind.infrastructure.agent.tool;

import com.shellmind.domain.agent.service.tool.LocalCommandToolService;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.shared.model.RunContext;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ADK adapter for LocalCommandToolService: declares tool parameters ({@code @Schema}),
 * loads the run context by session, then delegates to the domain service.
 * <p>
 * The method name is the tool name (ADK generates the tool description from it).
 * Keep the agent prompt in sync when renaming.
 */
@Component
public class LocalExecuteAdkTool {

    @Resource
    private LocalCommandToolService service;

    @Resource
    private AgentRunRegistry runRegistry;

    /**
     * Run a Shell command locally.
     *
     * @param command Shell command to execute
     * @return execution result (command, output, success, exitCode)
     */
    public Map<String, Object> executeLocalCommand(
            @Schema(name = "command", description = "Local Shell command to run, e.g. ls -la, mvn clean compile, npm run build, git status")
            String command,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("executeLocalCommand");
        }
        return service.executeLocalCommand(ctx, command);
    }

    private RunContext context(ToolContext toolContext) {
        return toolContext == null ? null : runRegistry.context(toolContext.sessionId()).orElse(null);
    }

    private Map<String, Object> missingContext(String toolName) {
        return Map.of("success", false, "error", toolName + " cannot run: run context is missing (session is not running)");
    }
}
