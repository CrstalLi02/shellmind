package com.shellmind.domain.coding.service;

import com.shellmind.domain.coding.adapter.port.ProcessRunner;
import com.shellmind.domain.coding.model.valobj.ProcessOutcome;
import com.shellmind.domain.coding.model.valobj.ProcessSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;

@Slf4j
@Service
public class WorkspaceStatusService {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    private final WorkspaceManager workspaceManager;
    private final ProcessRunner processRunner;

    public WorkspaceStatusService(WorkspaceManager workspaceManager, ProcessRunner processRunner) {
        this.workspaceManager = workspaceManager;
        this.processRunner = processRunner;
    }

    public WorkspaceStatus getStatus(String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) {
            return WorkspaceStatus.unavailable("No project path provided");
        }

        try {
            Path root = Path.of(projectRootPath).toAbsolutePath().normalize();
            WorkspaceManager.WorkspaceState state = workspaceManager.resolve(root);
            if (!state.gitRepository()) {
                return new WorkspaceStatus(root.toString(), null, null, false, "Not a Git repository");
            }

            String branch = runGit(root, "git", "rev-parse", "--abbrev-ref", "HEAD");
            String status = runGit(root, "git", "status", "--porcelain");
            String dirtySummary = status == null || status.isBlank()
                    ? "Working tree clean"
                    : summarizeStatus(status);

            return new WorkspaceStatus(root.toString(), branch, dirtySummary, true, null);
        } catch (Exception e) {
            log.warn("Failed to get workspace status: root={}", projectRootPath, e);
            return WorkspaceStatus.unavailable(e.getMessage());
        }
    }

    /**
     * Run a git command; returns null on timeout, non-zero exit, or if the process cannot start
     */
    private String runGit(Path root, String... command) {
        try {
            ProcessOutcome outcome = processRunner.run(ProcessSpec.of(root, GIT_TIMEOUT, command));
            return outcome.succeeded() ? outcome.output().trim() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String summarizeStatus(String porcelainOutput) {
        String[] lines = porcelainOutput.split("\n");
        int modified = 0, added = 0, deleted = 0, untracked = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            String code = line.substring(0, Math.min(2, line.length()));
            if (code.contains("??")) untracked++;
            else if (code.contains("D")) deleted++;
            else if (code.contains("A")) added++;
            else modified++;
        }
        StringBuilder sb = new StringBuilder();
        if (modified > 0) sb.append(modified).append(" modified ");
        if (added > 0) sb.append(added).append(" added ");
        if (deleted > 0) sb.append(deleted).append(" deleted ");
        if (untracked > 0) sb.append(untracked).append(" untracked");
        return sb.toString().trim();
    }

    public record WorkspaceStatus(
            String rootPath,
            String branch,
            String gitStatus,
            boolean gitRepository,
            String error) {

        public static WorkspaceStatus unavailable(String error) {
            return new WorkspaceStatus(null, null, null, false, error);
        }
    }
}
