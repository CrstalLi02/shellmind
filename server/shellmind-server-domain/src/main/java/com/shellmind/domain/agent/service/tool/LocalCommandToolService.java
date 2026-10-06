package com.shellmind.domain.agent.service.tool;

import com.shellmind.domain.agent.model.valobj.command.CommandResult;
import com.shellmind.domain.agent.adapter.port.LocalCommandExecutor;
import com.shellmind.domain.policy.service.ToolExecutionPolicyGuard;
import com.shellmind.domain.shared.model.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Local command tool (domain service): run a Shell command on the user's machine.
 * <p>
 * {@link LocalCommandExecutor} decides how it runs (local process or dispatched to the client).
 * Read-only policy and dangerous-command blocking are applied here.
 *
 * <p>Dispatched to the Client over the SSE command channel so the Server can be cloud-hosted:
 * <ul>
 *   <li>Server pushes an execute_local_command event to the Client over SSE</li>
 *   <li>Client runs it via Tauri locally, then HTTP POSTs the result back</li>
 *   <li>Server blocks on a CompletableFuture until the result arrives</li>
 * </ul>
 *
 * @author shellmind dev
 */
@Slf4j
@Service
public class LocalCommandToolService {

    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 60_000L;

    /** Max command-output characters (prevent huge logs from blowing the AI context) */
    private static final int MAX_OUTPUT_LENGTH = 30_000;

    /** Dangerous-command pattern (must be blocked) */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "\\b(rm\\s+-rf\\s+/|dd\\s+if=|mkfs\\.|:\\(\\)\\s*\\{|>\\s*/dev/sd|chmod\\s+-R\\s+777\\s+/)\\b",
            Pattern.CASE_INSENSITIVE
    );



    @Resource
    private LocalCommandExecutor localCommandExecutor;

    /**
     * Run a Shell command locally.
     *
     * @param command Shell command to run
     * @return execution result (command, output, success, exitCode)
     */
    public Map<String, Object> executeLocalCommand(RunContext ctx,
            String command) {
        return executeLocalCommand(ctx, command, null, DEFAULT_COMMAND_TIMEOUT_MS);
    }

    /**
     * Run a Shell command locally (optional working directory and timeout).
     *
     * @param command Shell command to run
     * @param cwd working directory (optional), e.g. /Users/xxx/projects/myapp
     * @param timeoutMs timeout in milliseconds, default 60000
     * @return execution result
     */
    public Map<String, Object> executeLocalCommand(RunContext ctx,
            String command,
            String cwd,
            Long timeoutMs) {

        long effectiveTimeoutMs = (timeoutMs != null && timeoutMs > 0) ? Math.min(timeoutMs, 300_000L) : DEFAULT_COMMAND_TIMEOUT_MS;

        log.info("[LocalExecute] command={}, cwd={}, timeoutMs={}", command, cwd, effectiveTimeoutMs);

        try {
            ToolExecutionPolicyGuard.requireCommandAllowed(ctx.readOnly(), command);
        } catch (ToolExecutionPolicyGuard.ToolPolicyViolationException e) {
            log.warn("[LocalExecute] Read-only policy blocked command: {}", command);
            Map<String, Object> blockedResult = Map.of(
                    "success", false,
                    "output", e.getMessage(),
                    "command", command
            );
            notifyToolResult("executeLocalCommand", command, blockedResult);
            return blockedResult;
        }

        // Dangerous-command check
        if (DANGEROUS_PATTERN.matcher(command).find()) {
            log.warn("[LocalExecute] Dangerous command blocked: {}", command);
            Map<String, Object> blockedResult = Map.of(
                    "success", false,
                    "output", "⚠️ Dangerous command blocked: " + command + "\nThis command may damage the system or cause data loss. If you really need it, run it manually in a terminal.",
                    "command", command
            );
            notifyToolResult("executeLocalCommand", command, blockedResult);
            return blockedResult;
        }

        try {
            // ═══════════════════════════════════════════════════════
            //  Prefer: dispatch to the Client over the SSE command channel
            // ═══════════════════════════════════════════════════════
            CommandResult cmdResult = localCommandExecutor.execute(
                    ctx.sessionId(), command, cwd, effectiveTimeoutMs);

            // Build the return map (HashMap allows null values)
            Map<String, Object> result = new HashMap<>();
            result.put("command", command);

            String safeOutput = cmdResult.getOutput() != null ? cmdResult.getOutput() : "";
            boolean truncated = safeOutput.length() > MAX_OUTPUT_LENGTH;
            if (truncated) {
                safeOutput = safeOutput.substring(0, MAX_OUTPUT_LENGTH)
                        + "\n... (output truncated, total " + safeOutput.length() + " chars)";
            }
            result.put("output", safeOutput);
            result.put("outputTruncated", truncated);
            result.put("success", cmdResult.isSuccess());
            result.put("exitCode", cmdResult.getExitCode());
            result.put("timeoutMs", effectiveTimeoutMs);

            if (!cmdResult.isSuccess()) {
                String suggestion = analyzeError(cmdResult.getOutput());
                if (suggestion != null) {
                    result.put("suggestion", suggestion);
                }
            }

            log.info("[LocalExecute] Command finished: success={}, outputLength={}, durationMs={}",
                    cmdResult.isSuccess(), safeOutput.length(), cmdResult.getDurationMs());
            notifyToolResult("executeLocalCommand", command, result);
            return result;

        } catch (Exception e) {
            log.error("[LocalExecute] Command execution exception: command={}", command, e);
            Map<String, Object> errResult = new java.util.HashMap<>();
            errResult.put("success", false);
            errResult.put("output", "Command execution exception: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            errResult.put("command", command);
            notifyToolResult("executeLocalCommand", command, errResult);
            return errResult;
        }
    }


    /**
     * Analyze the error and provide a suggested fix.
     */
    private String analyzeError(String output) {
        if (output == null) return null;

        String lowerOutput = output.toLowerCase();

        if (lowerOutput.contains("command not found")) {
            return "Command not found. Possible causes: typo, software not installed, or not on PATH. Check the command name or install the corresponding package.";
        }
        if (lowerOutput.contains("permission denied")) {
            return "Permission denied. Try sudo to elevate (macOS/Linux), or run as administrator (Windows).";
        }
        if (lowerOutput.contains("no such file or directory")) {
            return "File or directory does not exist. Check that the path is correct, or use an absolute path.";
        }
        if (lowerOutput.contains("cannot find") || lowerOutput.contains("could not find")) {
            return "File/dependency not found. Check the file path, dependency config, or whether the build tool is installed.";
        }
        if (lowerOutput.contains("compilation failed") || lowerOutput.contains("build failed")) {
            return "Compile/build failed. Check compilation errors and dependency issues in the code.";
        }
        return "Execution failed. Check the command syntax and the error details in the output.";
    }

    /**
     * Push the raw tool result (side channel disabled).
     * <p>After internalToolExecutionEnabled=false, tool results flow to AiCallNode via ADK
     * FunctionResponse events; the side channel is no longer needed and would duplicate output.
     */
    private void notifyToolResult(String toolName, String args, Map<String, Object> rawResult) {
        log.debug("[LocalExecuteAdkTool] notifyToolResult is disabled (side channel); tool results are pushed via ADK FunctionResponse");
    }
}
