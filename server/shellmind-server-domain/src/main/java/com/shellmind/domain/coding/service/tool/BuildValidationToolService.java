package com.shellmind.domain.coding.service.tool;

import com.shellmind.domain.coding.adapter.port.ProcessRunner;
import com.shellmind.domain.coding.model.valobj.ProcessOutcome;
import com.shellmind.domain.coding.model.valobj.ProcessSpec;
import com.shellmind.domain.shared.adapter.port.ToolProgressNotifier;
import com.shellmind.domain.ssh.service.ISshTerminalService;
import jakarta.annotation.Resource;
import com.shellmind.domain.shared.model.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile / test / lint verification tools
 *
 * <p>Lets the agent self-check after editing code:
 * <ul>
 *   <li>compileProject - detect project type and compile main sources</li>
 *   <li>compileTests - compile test sources (mvn test-compile / gradle testClasses)</li>
 *   <li>runUnitTests - run unit tests and extract a failure summary</li>
 *   <li>runLint - detect project type and run lint/static analysis</li>
 * </ul>
 *
 * <p>Execution mode:
 * <ul>
 *   <li>If the current run has a bound SSH terminal session, run on the remote host</li>
 *   <li>Otherwise run locally via bash -lc</li>
 * </ul>
 */
@Slf4j
@Service
public class BuildValidationToolService {

    private static final long COMPILE_TIMEOUT_MS = 120_000L;
    private static final long TEST_COMPILE_TIMEOUT_MS = 120_000L;
    private static final long TEST_RUN_TIMEOUT_MS = 300_000L;
    private static final long LINT_TIMEOUT_MS = 90_000L;
    private static final int MAX_OUTPUT_LENGTH = 20_000;
    /** Max lines in an error summary. */
    private static final int MAX_ERROR_SUMMARY_LINES = 50;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private ToolProgressNotifier progressNotifier;

    @Resource
    private ProcessRunner processRunner;

    public Map<String, Object> compileProject(RunContext ctx,
            String projectPath) {
        String command = buildCompileCommand(projectPath);
        return executeValidation(ctx, "compileProject", projectPath, command, COMPILE_TIMEOUT_MS);
    }

    /**
     * Compile test sources (main sources + test sources).
     *
     * <p>Used after the AI writes unit tests to check that the test code compiles.
     *
     * @param projectPath project directory
     * @return compile result (includes errorSummary with key error lines)
     */
    public Map<String, Object> compileTests(RunContext ctx,
            String projectPath) {
        String command = buildTestCompileCommand(projectPath);
        return executeValidation(ctx, "compileTests", projectPath, command, TEST_COMPILE_TIMEOUT_MS);
    }

    /**
     * Run unit tests.
     *
     * <p>Can target a test class or run all tests; returns a failure summary.
     *
     * @param projectPath project directory
     * @param testClass   fully qualified test class (optional, e.g. com.example.MyServiceTest); empty runs all tests
     * @return test result (includes testFailures with failing-case details)
     */
    public Map<String, Object> runUnitTests(RunContext ctx,
            String projectPath,
            String testClass) {
        String command = buildTestRunCommand(projectPath, testClass);
        return executeValidation(ctx, "runUnitTests", projectPath, command, TEST_RUN_TIMEOUT_MS);
    }

    public Map<String, Object> runLint(RunContext ctx,
            String projectPath) {
        String command = buildLintCommand(projectPath);
        return executeValidation(ctx, "runLint", projectPath, command, LINT_TIMEOUT_MS);
    }

    private Map<String, Object> executeValidation(RunContext ctx, String toolName, String projectPath, String command, long timeoutMs) {
        notifyProgress(ctx, toolName, projectPath);

        try {
            String terminalSessionId = ctx.terminalSessionId();
            boolean remote = ctx.hasTerminal();

            Map<String, Object> result = remote
                    ? executeRemote(toolName, projectPath, command, terminalSessionId, timeoutMs)
                    : executeLocal(toolName, projectPath, command, timeoutMs);

            boolean success = Boolean.TRUE.equals(result.get("success"));
            String summary = String.valueOf(result.getOrDefault("summary", success ? "Succeeded" : "Failed"));
            notifyProgressEnd(ctx, toolName, summary, success);
            notifyToolResult(toolName, projectPath, result);
            return result;
        } catch (Exception e) {
            log.error("[{}] Execution failed: path={}", toolName, projectPath, e);
            Map<String, Object> errResult = Map.of(
                    "success", false,
                    "path", projectPath,
                    "mode", "unknown",
                    "error", "Execution failed: " + e.getMessage(),
                    "summary", "Execution exception"
            );
            notifyProgressEnd(ctx, toolName, "Execution exception: " + e.getMessage(), false);
            notifyToolResult(toolName, projectPath, errResult);
            return errResult;
        }
    }

    private Map<String, Object> executeRemote(String toolName, String projectPath, String command, String terminalSessionId, long timeoutMs) {
        if (!sshTerminalService.sessionExists(terminalSessionId)) {
            return Map.of(
                    "success", false,
                    "path", projectPath,
                    "mode", "remote",
                    "error", "SSH terminal session missing or closed",
                    "summary", "Remote terminal session unavailable"
            );
        }

        log.info("[{}] Remote execution: session={}, path={}", toolName, terminalSessionId, projectPath);
        String output = sshTerminalService.executeCommand(terminalSessionId, command, timeoutMs);
        return buildResult(projectPath, "remote", command, output);
    }

    private Map<String, Object> executeLocal(String toolName, String projectPath, String command, long timeoutMs) throws Exception {
        log.info("[{}] Local execution: path={}", toolName, projectPath);

        ProcessOutcome outcome = processRunner.run(new ProcessSpec(
                List.of("bash", "-lc", command), Path.of("").toAbsolutePath(),
                Duration.ofMillis(timeoutMs), Map.of(), Integer.MAX_VALUE));
        if (outcome.timedOut()) {
            return Map.of(
                    "success", false,
                    "path", projectPath,
                    "mode", "local",
                    "command", command,
                    "output", "Execution timed out and was killed",
                    "summary", "Execution timed out"
            );
        }

        return buildResult(projectPath, "local", command, outcome.output(), outcome.exitCode());
    }

    private Map<String, Object> buildResult(String projectPath, String mode, String command, String output) {
        return buildResult(projectPath, mode, command, output, inferExitCode(output));
    }

    private Map<String, Object> buildResult(String projectPath, String mode, String command, String output, int exitCode) {
        String safeOutput = truncate(output);
        boolean success = exitCode == 0 && !safeOutput.contains("UNSUPPORTED_PROJECT") && !safeOutput.contains("UNSUPPORTED_LINT");

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("path", projectPath);
        result.put("mode", mode);
        result.put("command", command);
        result.put("exitCode", exitCode);
        result.put("output", safeOutput);
        result.put("summary", success ? "Validation passed" : "Validation failed");

        if (!success) {
            result.put("error", safeOutput);
            // Extract key error lines so the AI can locate the problem quickly
            String errorSummary = extractErrorSummary(output);
            if (errorSummary != null && !errorSummary.isBlank()) {
                result.put("errorSummary", errorSummary);
            }
        }

        // Test-run result: extract failing cases
        if (output != null && (output.contains("Tests run:") || output.contains("BUILD FAILURE"))) {
            String testFailures = extractTestFailures(output);
            if (testFailures != null && !testFailures.isBlank()) {
                result.put("testFailures", testFailures);
            }
        }

        return result;
    }

    private String buildCompileCommand(String projectPath) {
        String path = shellQuote(projectPath);
        return "cd " + path + " && " +
                "if [ -f pom.xml ]; then mvn -q -DskipTests compile; " +
                "elif [ -f gradlew ]; then chmod +x ./gradlew >/dev/null 2>&1; ./gradlew classes; " +
                "elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then gradle classes; " +
                "elif [ -f package.json ]; then npm run build --if-present && npm run typecheck --if-present; " +
                "elif [ -f go.mod ]; then go build ./...; " +
                "elif [ -f Cargo.toml ]; then cargo check; " +
                "elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then python -m compileall .; " +
                "else echo UNSUPPORTED_PROJECT: unrecognized project type; exit 2; fi";
    }

    /**
     * Build the test-compile command (main sources + test sources).
     */
    private String buildTestCompileCommand(String projectPath) {
        String path = shellQuote(projectPath);
        return "cd " + path + " && " +
                "if [ -f pom.xml ]; then mvn -q test-compile; " +
                "elif [ -f gradlew ]; then chmod +x ./gradlew >/dev/null 2>&1; ./gradlew testClasses; " +
                "elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then gradle testClasses; " +
                "elif [ -f package.json ]; then npm run build --if-present && npx tsc --noEmit --project tsconfig.json 2>/dev/null || true; " +
                "elif [ -f go.mod ]; then go vet ./...; " +
                "elif [ -f Cargo.toml ]; then cargo test --no-run; " +
                "elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then python -m pytest --collect-only -q 2>/dev/null || true; " +
                "else echo UNSUPPORTED_PROJECT: unrecognized project type; exit 2; fi";
    }

    /**
     * Build the unit-test run command.
     */
    private String buildTestRunCommand(String projectPath, String testClass) {
        String path = shellQuote(projectPath);
        String testFilter = (testClass != null && !testClass.isBlank()) ? " -Dtest=" + shellQuote(testClass) : "";
        return "cd " + path + " && " +
                "if [ -f pom.xml ]; then mvn test" + testFilter + " -q 2>&1; " +
                "elif [ -f gradlew ]; then chmod +x ./gradlew >/dev/null 2>&1; ./gradlew test" +
                    (testClass != null && !testClass.isBlank() ? " --tests " + shellQuote(testClass) : "") + "; " +
                "elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then gradle test" +
                    (testClass != null && !testClass.isBlank() ? " --tests " + shellQuote(testClass) : "") + "; " +
                "elif [ -f package.json ]; then npm test 2>&1; " +
                "elif [ -f go.mod ]; then go test ./... -v 2>&1; " +
                "elif [ -f Cargo.toml ]; then cargo test 2>&1; " +
                "elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then python -m pytest -v 2>&1; " +
                "else echo UNSUPPORTED_PROJECT: unrecognized project type; exit 2; fi";
    }

    /**
     * Extract key error lines from compile/test output ([ERROR] / error: / FAILED).
     *
     * <p>Avoid sending hundreds of Maven log lines to the AI; keep only key errors.
     */
    private String extractErrorSummary(String output) {
        if (output == null || output.isBlank()) return null;

        java.util.List<String> errorLines = new java.util.ArrayList<>();
        String[] lines = output.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (errorLines.size() >= MAX_ERROR_SUMMARY_LINES) break;

            // Maven [ERROR] lines
            if (trimmed.startsWith("[ERROR]")) {
                errorLines.add(trimmed);
                continue;
            }
            // Compiler error: lines
            String lower = trimmed.toLowerCase();
            if (lower.contains("error:") || lower.contains("cannot find symbol")
                    || lower.contains("package does not exist")
                    || lower.contains("找不到符号") || lower.contains("程序包不存在")) {
                errorLines.add(trimmed);
                continue;
            }
            // Gradle failure lines
            if (lower.contains("execution failed for task") || lower.contains("what went wrong:")) {
                errorLines.add(trimmed);
                continue;
            }
            // Generic compile failure
            if (lower.contains("compilation failed") || lower.contains("build failed")) {
                errorLines.add(trimmed);
            }
        }

        if (errorLines.isEmpty()) return null;
        return String.join("\n", errorLines);
    }

    /**
     * Extract a failing-test summary from test output.
     *
     * <p>Extract "Tests run:" stats and "FAILED" case lines so the AI can locate failing tests quickly.
     */
    private String extractTestFailures(String output) {
        if (output == null || output.isBlank()) return null;

        java.util.List<String> failureLines = new java.util.ArrayList<>();
        String[] lines = output.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (failureLines.size() >= MAX_ERROR_SUMMARY_LINES) break;

            // Maven Surefire test stats
            if (trimmed.contains("Tests run:") && (trimmed.contains("Failures:") || trimmed.contains("Errors:"))) {
                failureLines.add(trimmed);
                continue;
            }
            // FAILED lines
            if (trimmed.contains("FAILED") || trimmed.contains("<<< FAILURE!") || trimmed.contains("<<< ERROR!")) {
                failureLines.add(trimmed);
                continue;
            }
            // Gradle test failures
            String lower = trimmed.toLowerCase();
            if (lower.contains("test failed") || lower.contains("tests failed")) {
                failureLines.add(trimmed);
            }
        }

        if (failureLines.isEmpty()) return null;
        return String.join("\n", failureLines);
    }

    private String buildLintCommand(String projectPath) {
        String path = shellQuote(projectPath);
        return "cd " + path + " && " +
                "if [ -f package.json ]; then npm run lint --if-present; " +
                "elif [ -f Cargo.toml ]; then cargo clippy --quiet -- -D warnings; " +
                "elif [ -f go.mod ]; then (golangci-lint run || go vet ./...); " +
                "elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then (ruff check . || flake8 .); " +
                "elif [ -f pom.xml ] || [ -f build.gradle ] || [ -f build.gradle.kts ]; then echo UNSUPPORTED_LINT: this Java project has no unified lint command; exit 2; " +
                "else echo UNSUPPORTED_LINT: unrecognized project type; exit 2; fi";
    }

    private String shellQuote(String value) {
        if (value == null || value.isBlank()) {
            return "'.'";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }


    private String truncate(String output) {
        if (output == null) return "";
        if (output.length() <= MAX_OUTPUT_LENGTH) return output;
        return output.substring(0, MAX_OUTPUT_LENGTH) + "\n... (truncated, total " + output.length() + " chars)";
    }

    private int inferExitCode(String output) {
        if (output == null) return 0;
        if (output.contains("BUILD SUCCESS") || output.contains("Validation passed") || output.contains("校验通过")) return 0;
        if (output.contains("UNSUPPORTED_PROJECT") || output.contains("UNSUPPORTED_LINT")) return 2;
        return containsFailure(output) ? 1 : 0;
    }

    private boolean containsFailure(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("exception")
                || lower.contains("permission denied")
                || lower.contains("command not found")
                || lower.contains("cannot find");
    }

    private void notifyProgress(RunContext ctx, String toolName, String args) {
        if (progressNotifier != null) {
            try {
                progressNotifier.onToolStart(ctx.sessionId(), toolName, args);
            } catch (Exception e) {
                log.debug("Progress notification failed (does not affect tool execution)", e);
            }
        }
    }

    private void notifyProgressEnd(RunContext ctx, String toolName, String summary, boolean success) {
        if (progressNotifier != null) {
            try {
                progressNotifier.onToolEnd(ctx.sessionId(), toolName, summary, success);
            } catch (Exception e) {
                log.debug("Progress notification failed (does not affect tool execution)", e);
            }
        }
    }

    /**
     * Push the raw tool result (side channel disabled).
     * <p>With internalToolExecutionEnabled=false, tool results travel on the ADK event stream as
     * FunctionResponse to AiCallNode; the side channel is no longer needed and would duplicate results.
     */
    private void notifyToolResult(String toolName, String args, Map<String, Object> rawResult) {
        log.debug("[BuildValidationAdkTool] notifyToolResult disabled (side channel); tool results are pushed via ADK FunctionResponse");
    }
}
