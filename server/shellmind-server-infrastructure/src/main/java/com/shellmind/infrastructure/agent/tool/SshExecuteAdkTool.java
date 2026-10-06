package com.shellmind.infrastructure.agent.tool;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.agent.service.tool.RemoteCommandToolService;
import com.shellmind.domain.shared.model.RunContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ADK adapter for RemoteCommandToolService: run a command on the SSH terminal bound to the current session.
 */
@Component
public class SshExecuteAdkTool {

    @Resource
    private RemoteCommandToolService service;

    @Resource
    private AgentRunRegistry runRegistry;

    /**
     * Run a command on the SSH terminal.
     *
     * @param command Shell command to execute
     * @return execution result
     */
    public Map<String, Object> executeCommand(
            @Schema(name = "command", description = "Shell command to run, e.g. ls -la, apt install docker.io, docker --version")
            String command,
            ToolContext toolContext) {
        RunContext ctx = toolContext == null ? null : runRegistry.context(toolContext.sessionId()).orElse(null);
        if (ctx == null) {
            return Map.of("success", false, "output", "executeCommand cannot run: run context is missing (session is not running)", "command", command);
        }
        return service.executeCommand(ctx, command);
    }
}
