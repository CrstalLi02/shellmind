package com.shellmind.domain.agent.service.tool;

import com.shellmind.domain.shared.model.RunContext;
import com.shellmind.domain.ssh.service.ISshTerminalService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Remote command tool (domain service): run a command on the SSH terminal bound to the current run.
 * <p>
 * The terminal session is taken only from {@link RunContext#terminalSessionId()}: if unbound,
 * return an error immediately. Never fall back to another session's terminal (that would send
 * commands to someone else's server).
 */
@Slf4j
@Service
public class RemoteCommandToolService {

    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 60_000L;

    /** Dangerous commands (blocked outright; the user must run them manually in a terminal) */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "\\b(rm\\s+-rf\\s+/|dd\\s+if=|mkfs\\.|:\\(\\)\\s*\\{|>\\s*/dev/sd|chmod\\s+-R\\s+777\\s+/)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final String[] ERROR_INDICATORS = {
            "command not found", "no such file or directory", "permission denied",
            "operation not permitted", "cannot find", "error:", "failed",
            "fatal:", "unable to", "connection refused", "network is unreachable"
    };

    @Resource
    private ISshTerminalService sshTerminalService;

    public Map<String, Object> executeCommand(RunContext ctx, String command) {
        return executeCommand(ctx, command, DEFAULT_COMMAND_TIMEOUT_MS);
    }

    /**
     * @param timeoutMs maximum wait in milliseconds; empty or non-positive uses the default
     */
    public Map<String, Object> executeCommand(RunContext ctx, String command, Long timeoutMs) {
        long effectiveTimeoutMs = effectiveTimeout(timeoutMs);
        Map<String, Object> rejected = precheck(ctx, command, true);
        if (rejected != null) {
            return rejected;
        }
        String terminalSessionId = ctx.terminalSessionId();
        try {
            log.info("SSH executing command: session={}, terminal={}, timeoutMs={}, command={}",
                    ctx.sessionId(), terminalSessionId, effectiveTimeoutMs, command);
            String output = sshTerminalService.executeCommand(terminalSessionId, command, effectiveTimeoutMs);
            log.info("SSH command finished: outputLength={}", output.length());
            return buildResult(command, output, effectiveTimeoutMs);
        } catch (Exception e) {
            log.error("SSH command execution exception: terminal={}, command={}", terminalSessionId, command, e);
            return failure(command, "Command execution exception: " + e.getMessage());
        }
    }

    /**
     * Streaming execution: each output chunk is delivered in real time via chunkCallback.
     */
    public Map<String, Object> executeCommandStreaming(RunContext ctx, String command, Long timeoutMs,
                                                       Consumer<String> chunkCallback) {
        long effectiveTimeoutMs = effectiveTimeout(timeoutMs);
        Map<String, Object> rejected = precheck(ctx, command, false);
        if (rejected != null) {
            return rejected;
        }
        String terminalSessionId = ctx.terminalSessionId();
        try {
            log.info("SSH streaming command: session={}, terminal={}, timeoutMs={}, command={}",
                    ctx.sessionId(), terminalSessionId, effectiveTimeoutMs, command);
            String output = sshTerminalService.executeCommandStreaming(
                    terminalSessionId, command, effectiveTimeoutMs, chunkCallback);
            return buildResult(command, output, effectiveTimeoutMs);
        } catch (Exception e) {
            log.error("SSH streaming command exception: terminal={}, command={}", terminalSessionId, command, e);
            return failure(command, "Command execution exception: " + e.getMessage());
        }
    }

    /**
     * Pre-execution checks: terminal is bound, session exists, command is not dangerous.
     * Returns null if execution may proceed.
     *
     * @param verboseDangerMessage whether the dangerous-command message includes handling advice
     */
    private Map<String, Object> precheck(RunContext ctx, String command, boolean verboseDangerMessage) {
        if (!ctx.hasTerminal()) {
            log.warn("[executeCommand] Session has no bound SSH terminal, cannot run command: session={}", ctx.sessionId());
            return failure(command, "No SSH terminal session is bound. Please open an SSH terminal connection first.");
        }
        if (!sshTerminalService.sessionExists(ctx.terminalSessionId())) {
            log.warn("[executeCommand] Terminal session does not exist: {}", ctx.terminalSessionId());
            return failure(command, "SSH terminal session does not exist or has been closed: " + ctx.terminalSessionId());
        }
        if (DANGEROUS_PATTERN.matcher(command).find()) {
            return failure(command, verboseDangerMessage
                    ? "⚠️ Dangerous command blocked: " + command + "\nThis command may damage the system or cause data loss. If you really need it, run it manually in a terminal."
                    : "⚠️ Dangerous command blocked: " + command);
        }
        return null;
    }

    private Map<String, Object> buildResult(String command, String output, long timeoutMs) {
        boolean success = isExecutionSuccessful(output);
        Map<String, Object> result = new HashMap<>();
        result.put("command", command);
        result.put("output", output);
        result.put("success", success);
        result.put("timeoutMs", timeoutMs);
        if (!success) {
            result.put("suggestion", analyzeError(output));
        }
        return result;
    }

    private Map<String, Object> failure(String command, String output) {
        return Map.of("success", false, "output", output, "command", command);
    }

    private long effectiveTimeout(Long timeoutMs) {
        return timeoutMs == null || timeoutMs <= 0 ? DEFAULT_COMMAND_TIMEOUT_MS : timeoutMs;
    }

    private boolean isExecutionSuccessful(String output) {
        if (output == null || output.isEmpty()) {
            return true;
        }
        String lowerOutput = output.toLowerCase();
        for (String indicator : ERROR_INDICATORS) {
            if (lowerOutput.contains(indicator)) {
                return false;
            }
        }
        return true;
    }

    private String analyzeError(String output) {
        if (output == null) return null;
        String lowerOutput = output.toLowerCase();
        if (lowerOutput.contains("command not found")) {
            return "Command not found. Possible causes: typo, software not installed, or not on PATH. Check the command name or install the corresponding package.";
        }
        if (lowerOutput.contains("permission denied")) {
            return "Permission denied. Try sudo to elevate, or check file/directory permissions.";
        }
        if (lowerOutput.contains("no such file or directory")) {
            return "File or directory does not exist. Check that the path is correct, or use an absolute path.";
        }
        if (lowerOutput.contains("connection refused") || lowerOutput.contains("network is unreachable")) {
            return "Network connectivity issue. Check the network, confirm the target service is running, and review firewall settings.";
        }
        return "Execution failed. Check the command and the output.";
    }
}
