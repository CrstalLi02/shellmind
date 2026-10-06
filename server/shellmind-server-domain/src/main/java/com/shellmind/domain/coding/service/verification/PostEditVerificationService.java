package com.shellmind.domain.coding.service.verification;

import com.shellmind.domain.coding.adapter.port.ProcessRunner;
import com.shellmind.domain.coding.model.valobj.ProcessOutcome;
import com.shellmind.domain.coding.model.valobj.ProcessSpec;
import com.shellmind.domain.coding.service.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PostEditVerificationService {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_OUTPUT_LINES = 200;

    private final VerificationEngine verificationEngine;
    private final WorkspaceManager workspaceManager;
    private final ProcessRunner processRunner;

    public PostEditVerificationService(VerificationEngine verificationEngine,
                                        WorkspaceManager workspaceManager,
                                        ProcessRunner processRunner) {
        this.verificationEngine = verificationEngine;
        this.workspaceManager = workspaceManager;
        this.processRunner = processRunner;
    }

    public VerificationResult verifyAfterEdit(String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) {
            return VerificationResult.skipped("No project path provided");
        }

        try {
            Path root = Path.of(projectRootPath).toAbsolutePath().normalize();
            workspaceManager.resolve(root);
            VerificationPlan plan = verificationEngine.plan(root);

            if ("unknown".equals(plan.projectType()) || plan.commands().isEmpty()) {
                return VerificationResult.skipped("Unrecognized project type; skipping automatic verification");
            }

            ProcessOutcome outcome = processRunner.run(new ProcessSpec(
                    plan.commands(), root, VERIFY_TIMEOUT,
                    Map.of("SHELLMIND_WORKSPACE", projectRootPath), MAX_OUTPUT_LINES));
            List<String> outputLines = outcome.outputLines();
            if (outcome.timedOut()) {
                return VerificationResult.failed(plan.projectType(), "Verification timed out (120s)", truncate(outputLines));
            }

            int exitCode = outcome.exitCode();
            String output = truncate(outputLines);
            if (exitCode == 0) {
                return VerificationResult.passed(plan.projectType(), output);
            } else {
                return VerificationResult.failed(plan.projectType(), "exit code: " + exitCode, output);
            }
        } catch (Exception e) {
            log.error("Automatic verification failed: root={}", projectRootPath, e);
            return VerificationResult.failed("unknown", e.getMessage(), "");
        }
    }

    private String truncate(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() + line.length() > 5000) {
                sb.append("\n... (output truncated)");
                break;
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    public record VerificationResult(
            boolean verified,
            String projectType,
            String message,
            String output,
            String errorSummary,
            boolean skipped) {

        public static VerificationResult passed(String projectType, String output) {
            return new VerificationResult(true, projectType, "Verification passed", output, null, false);
        }

        public static VerificationResult failed(String projectType, String message, String output) {
            String summary = extractErrorSummary(output);
            return new VerificationResult(false, projectType, message, output, summary, false);
        }

        public static VerificationResult skipped(String reason) {
            return new VerificationResult(false, "unknown", reason, "", null, true);
        }

        private static String extractErrorSummary(String output) {
            if (output == null || output.isBlank()) return null;
            List<String> errorLines = new ArrayList<>();
            String[] lines = output.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (errorLines.size() >= 30) break;
                String lower = trimmed.toLowerCase();
                if (trimmed.startsWith("[ERROR]")
                        || lower.contains("error:")
                        || lower.contains("cannot find symbol")
                        || lower.contains("package does not exist")
                        || lower.contains("找不到符号")
                        || lower.contains("程序包不存在")
                        || lower.contains("execution failed for task")
                        || lower.contains("what went wrong:")
                        || lower.contains("compilation failed")
                        || lower.contains("build failed")
                        || lower.contains("npm err!")) {
                    errorLines.add(trimmed);
                }
            }
            return errorLines.isEmpty() ? null : String.join("\n", errorLines);
        }
    }
}
