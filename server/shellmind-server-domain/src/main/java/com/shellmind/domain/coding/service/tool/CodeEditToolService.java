package com.shellmind.domain.coding.service.tool;

import com.shellmind.domain.shared.adapter.port.ToolProgressNotifier;
import com.shellmind.domain.ssh.model.entity.SshFileContentEntity;
import com.shellmind.domain.ssh.model.entity.SshFileTreeEntity;
import com.shellmind.domain.ssh.service.ISshFileDomainService;
import com.shellmind.domain.ssh.service.ISshTerminalService;
import com.shellmind.domain.ssh.model.entity.TerminalSessionEntity;
import com.shellmind.domain.coding.service.workspace.WorkspacePathGuard;
import com.shellmind.domain.policy.service.ToolExecutionPolicyGuard;
import com.shellmind.domain.coding.service.CodePatchService;
import com.shellmind.domain.shared.model.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Code read/write tools (domain service): file I/O, search, rollback, and verification on the local workspace and remote SSH hosts.
 * <p>
 * Every method takes an explicit {@link RunContext}: workspace bounds, read-only policy, terminal session, and audit identity come from it.
 * Exposed as agent tools by the infrastructure ADK adapter (CodeEditAdkTool).
 *
 * <p>Gives the agent remote-server code read/write capabilities:
 * <ul>
 *   <li>readFile - read a remote file (supports chunking)</li>
 *   <li>writeFile - write/overwrite a remote file</li>
 *   <li>listFiles - list a directory</li>
 *   <li>searchInFiles - search a remote directory for a keyword (grep)</li>
 * </ul>
 *
 * <p>Maps terminalSessionId → connectionId and reuses the existing SSH file service.
 *
 * @author shellmind dev
 */
@Slf4j
@Service
public class CodeEditToolService {

    @Resource
    private ISshFileDomainService sshFileDomainService;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private ToolProgressNotifier progressNotifier;

    @Resource
    private WorkspacePathGuard workspacePathGuard;

    @Resource
    private CodePatchService codePatchService;

    @Resource
    private com.shellmind.domain.coding.service.CodePatchAuditService codePatchAuditService;

    @Resource
    private com.shellmind.domain.coding.service.verification.PostEditVerificationService postEditVerificationService;

    @Resource
    private com.shellmind.domain.coding.service.RepositoryIndexService repositoryIndexService;

    /** Max characters to read (2M, enough for most source files). */
    private static final int MAX_READ_LENGTH = 2_000_000;

    /** Max directory listing entries. */
    private static final int MAX_DIR_ENTRIES = 200;


    // ═══════════════════════════════════════════════════════════════
    //  Local file operations (no SSH; uses Java I/O directly)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Read a local file.
     *
     * @param filePath absolute file path
     * @return file content
     */
    public Map<String, Object> readLocalFile(RunContext ctx,
            String filePath) {

        try {
            ToolExecutionPolicyGuard.requireMutationAllowed(ctx.readOnly(), "readLocalFile");
            Path path = workspacePathGuard.resolve(ctx.workspace(), filePath);
            log.info("[CodeEdit-Local] Reading local file: path={}", filePath);
            notifyProgress(ctx, "readLocalFile", filePath);

            if (!Files.exists(path)) {
                return Map.of("success", false, "error", "File not found: " + filePath, "path", filePath);
            }

            if (Files.isDirectory(path)) {
                return Map.of("success", false, "error", "Path is a directory, not a file: " + filePath, "path", filePath);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            boolean truncated = false;
            long size = Files.size(path);

            if (content.length() > MAX_READ_LENGTH) {
                content = content.substring(0, MAX_READ_LENGTH);
                truncated = true;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", filePath);
            result.put("name", path.getFileName().toString());
            result.put("size", size);
            result.put("truncated", truncated);
            result.put("content", content);

            if (truncated) {
                result.put("note", "File content exceeds " + MAX_READ_LENGTH + " characters and was truncated.");
            }

            log.info("[CodeEdit-Local] Read local file: path={}, size={}", filePath, size);
            notifyProgressEnd(ctx, "readLocalFile", "Read succeeded, " + size + " bytes", true);
            return result;

        } catch (Exception e) {
            log.error("[CodeEdit-Local] Failed to read local file: path={}", filePath, e);
            return Map.of("success", false, "error", "Failed to read local file: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * Write/overwrite a local file.
     *
     * @param filePath absolute file path
     * @param content  file content
     * @return write result
     */
    public Map<String, Object> writeLocalFile(RunContext ctx,
            String filePath,
            String content) {

        try {
            ToolExecutionPolicyGuard.requireMutationAllowed(ctx.readOnly(), "writeLocalFile");
            Path path = workspacePathGuard.resolve(ctx.workspace(), filePath);
            log.info("[CodeEdit-Local] Writing local file: path={}, contentLength={}",
                    filePath, content != null ? content.length() : 0);
            notifyProgress(ctx, "writeLocalFile", filePath);

            CodePatchService.CodePatchResult patchResult = codePatchService.writeWithBackup(path, content);
            long bytesWritten = content != null ? content.length() : 0;

            log.info("[CodeEdit-Local] Wrote local file: path={}, bytes={}", filePath, bytesWritten);
            notifyProgressEnd(ctx, "writeLocalFile", "Write succeeded, " + bytesWritten + " bytes", true);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", filePath);
            result.put("bytesWritten", bytesWritten);
            result.put("message", "File written: " + filePath);
            result.put("unifiedDiff", patchResult.unifiedDiff());
            if (patchResult.backupPath() != null) {
                result.put("backupPath", patchResult.backupPath());
            }

            try {
                com.shellmind.domain.coding.model.entity.CodePatchEntity patchEntity =
                        codePatchAuditService.recordPatch(ctx.runId(), ctx.sessionId(), ctx.userId(),
                                filePath, patchResult.backupPath(), patchResult.unifiedDiff());
                result.put("patchId", patchEntity.getPatchId());
                result.put("patchAudit", "Change recorded in patch audit");
            } catch (Exception auditException) {
                log.warn("Failed to record patch audit: path={}", filePath, auditException);
            }

            // Invalidate index: clear cache after a write so the next recall uses the latest files
            String projectRoot = inferProjectRoot(path);
            if (projectRoot != null) {
                repositoryIndexService.invalidateCache(projectRoot);
            }

            result.put("verificationHint", "File modified. After all edits, call verifyLocalProject to verify. If verification fails, call rollbackLocalFile to roll back.");
            return result;

        } catch (Exception e) {
            log.error("[CodeEdit-Local] Failed to write local file: path={}", filePath, e);
            return Map.of("success", false, "error", "Failed to write local file: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * List a local directory.
     *
     * @param dirPath directory path
     * @return directory listing
     */
    public Map<String, Object> listLocalFiles(RunContext ctx,
            String dirPath) {

        try {
            Path path = workspacePathGuard.resolve(ctx.workspace(), dirPath);
            log.info("[CodeEdit-Local] Listing local directory: path={}", dirPath);
            notifyProgress(ctx, "listLocalFiles", dirPath);

            if (!Files.exists(path)) {
                return Map.of("success", false, "error", "Directory not found: " + dirPath, "path", dirPath);
            }

            if (!Files.isDirectory(path)) {
                return Map.of("success", false, "error", "Path is not a directory: " + dirPath, "path", dirPath);
            }

            List<Map<String, Object>> items = new ArrayList<>();
            int count = 0;
            try (Stream<Path> stream = Files.list(path)) {
                List<Path> sorted = stream.sorted().collect(Collectors.toList());
                for (Path item : sorted) {
                    if (count >= MAX_DIR_ENTRIES) break;
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("name", item.getFileName().toString());
                    itemMap.put("path", item.toAbsolutePath().toString());
                    itemMap.put("directory", Files.isDirectory(item));
                    try {
                        itemMap.put("size", Files.size(item));
                    } catch (IOException ignored) {
                        itemMap.put("size", 0);
                    }
                    items.add(itemMap);
                    count++;
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", path.toAbsolutePath().toString());
            result.put("parentPath", path.getParent() != null ? path.getParent().toAbsolutePath().toString() : null);
            result.put("items", items);
            result.put("total", items.size());
            result.put("truncated", count >= MAX_DIR_ENTRIES);

            log.info("[CodeEdit-Local] Listed local directory: path={}, items={}", dirPath, items.size());
            notifyProgressEnd(ctx, "listLocalFiles", "Listed " + items.size() + " entries", true);
            return result;

        } catch (Exception e) {
            log.error("[CodeEdit-Local] Failed to list local directory: path={}", dirPath, e);
            return Map.of("success", false, "error", "Failed to list local directory: " + e.getMessage(), "path", dirPath);
        }
    }

    /**
     * Search local files for a keyword.
     *
     * @param directory search directory
     * @param keyword   search keyword
     * @return search results
     */
    public Map<String, Object> searchInLocalFiles(RunContext ctx,
            String directory,
            String keyword) {

        try {
            Path searchPath = workspacePathGuard.resolve(ctx.workspace(), directory);
            log.info("[CodeEdit-Local] Searching local files: dir={}, keyword={}", directory, keyword);
            notifyProgress(ctx, "searchInLocalFiles", directory + " (keyword: " + keyword + ")");

            if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
                return Map.of("success", false, "error", "Directory not found or is not a directory: " + directory);
            }

            List<Map<String, Object>> matches = new ArrayList<>();
            String[] extensions = {".java", ".py", ".js", ".ts", ".tsx", ".go", ".rs", ".c", ".cpp", ".h",
                    ".yml", ".yaml", ".json", ".xml", ".sh", ".conf", ".md", ".txt", ".sql", ".html", ".css", ".vue"};
            Set<String> extSet = new HashSet<>(Arrays.asList(extensions));

            Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= 100) return FileVisitResult.TERMINATE;

                    String fileName = file.getFileName().toString();
                    boolean hasValidExt = false;
                    for (String ext : extSet) {
                        if (fileName.endsWith(ext)) {
                            hasValidExt = true;
                            break;
                        }
                    }
                    if (!hasValidExt) return FileVisitResult.CONTINUE;

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).contains(keyword)) {
                                Map<String, Object> match = new HashMap<>();
                                match.put("file", file.toAbsolutePath().toString());
                                match.put("line", String.valueOf(i + 1));
                                match.put("content", lines.get(i).trim());
                                matches.add(match);
                                if (matches.size() >= 100) return FileVisitResult.TERMINATE;
                            }
                        }
                    } catch (IOException ignored) {
                        // Skip unreadable files
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    // Skip hidden and commonly ignored directories
                    if (name.startsWith(".") || name.equals("node_modules") || name.equals("target")
                            || name.equals("build") || name.equals("dist") || name.equals("__pycache__")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("directory", directory);
            result.put("keyword", keyword);
            result.put("matches", matches);
            result.put("total", matches.size());

            log.info("[CodeEdit-Local] Search complete: keyword={}, matches={}", keyword, matches.size());
            notifyProgressEnd(ctx, "searchInLocalFiles", "Search complete, " + matches.size() + " matches", true);
            return result;

        } catch (Exception e) {
            log.error("[CodeEdit-Local] Failed to search local files: dir={}, keyword={}", directory, keyword, e);
            return Map.of("success", false, "error", "Failed to search local files: " + e.getMessage());
        }
    }

    /**
     * Create a new local file.
     *
     * @param filePath file path
     * @return create result
     */
    public Map<String, Object> createLocalFile(RunContext ctx,
            String filePath) {

        try {
            ToolExecutionPolicyGuard.requireMutationAllowed(ctx.readOnly(), "createLocalFile");
            Path path = workspacePathGuard.resolve(ctx.workspace(), filePath);
            log.info("[CodeEdit-Local] Creating local file: path={}", filePath);
            notifyProgress(ctx, "createLocalFile", filePath);

            if (Files.exists(path)) {
                return Map.of("success", false, "error", "File already exists: " + filePath, "path", filePath);
            }

            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.createFile(path);
            log.info("[CodeEdit-Local] Created local file: path={}", filePath);
            notifyProgressEnd(ctx, "createLocalFile", "File created", true);

            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "File created: " + filePath
            );

        } catch (Exception e) {
            log.error("[CodeEdit-Local] Failed to create local file: path={}", filePath, e);
            return Map.of("success", false, "error", "Failed to create local file: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * Delete a local file.
     *
     * @param filePath file path
     * @return delete result
     */
    public Map<String, Object> deleteLocalFile(RunContext ctx,
            String filePath) {

        try {
            ToolExecutionPolicyGuard.requireMutationAllowed(ctx.readOnly(), "deleteLocalFile");
            Path path = workspacePathGuard.resolve(ctx.workspace(), filePath);
            log.info("[CodeEdit-Local] Deleting local file: path={}", filePath);
            notifyProgress(ctx, "deleteLocalFile", filePath);

            if (!Files.exists(path)) {
                return Map.of("success", false, "error", "File not found: " + filePath, "path", filePath);
            }

            Files.delete(path);
            log.info("[CodeEdit-Local] Deleted local file: path={}", filePath);
            notifyProgressEnd(ctx, "deleteLocalFile", "File deleted", true);

            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "File deleted: " + filePath
            );

        } catch (Exception e) {
            log.error("[CodeEdit-Local] Failed to delete local file: path={}", filePath, e);
            return Map.of("success", false, "error", "Failed to delete local file: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * Roll back to the backup taken before the last ShellMind write.
     */
    public Map<String, Object> rollbackLocalFile(RunContext ctx,
            String filePath,
            String backupPath) {

        try {
            Path path = workspacePathGuard.resolve(ctx.workspace(), filePath);
            Path backup = workspacePathGuard.resolve(ctx.workspace(), backupPath);
            if (backup.getParent() == null || path.getParent() == null
                    || !backup.getParent().equals(path.getParent())) {
                return Map.of("success", false, "error", "Backup file must be in the same directory as the target");
            }

            notifyProgress(ctx, "rollbackLocalFile", filePath);
            boolean restored = codePatchService.rollback(path, backup.toString());
            if (!restored) {
                notifyProgressEnd(ctx, "rollbackLocalFile", "Backup not found", false);
                return Map.of("success", false, "error", "Backup file not found: " + backupPath);
            }

            Files.deleteIfExists(backup);
            notifyProgressEnd(ctx, "rollbackLocalFile", "File rolled back", true);
            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "File rolled back: " + filePath
            );
        } catch (Exception e) {
            log.error("[CodeEdit-Local] Failed to roll back local file: path={}, backup={}", filePath, backupPath, e);
            return Map.of("success", false, "error", "Failed to roll back local file: " + e.getMessage());
        }
    }

    /**
     * Called after the AI finishes editing: detect project type → compile/verify → return a structured result.
     */
    public Map<String, Object> verifyLocalProject(RunContext ctx,
            String projectPath) {

        try {
            Path resolvedProjectPath = workspacePathGuard.resolve(ctx.workspace(), projectPath);
            String absoluteProjectPath = resolvedProjectPath.toAbsolutePath().normalize().toString();
            notifyProgress(ctx, "verifyLocalProject", absoluteProjectPath);
            com.shellmind.domain.coding.service.verification.PostEditVerificationService.VerificationResult vr =
                    postEditVerificationService.verifyAfterEdit(absoluteProjectPath);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("projectPath", absoluteProjectPath);
            result.put("projectType", vr.projectType());
            result.put("verified", vr.verified());
            result.put("skipped", vr.skipped());
            result.put("message", vr.message());
            if (vr.output() != null && !vr.output().isBlank()) {
                result.put("output", vr.output());
            }
            if (vr.errorSummary() != null && !vr.errorSummary().isBlank()) {
                result.put("errorSummary", vr.errorSummary());
            }
            if (!vr.verified() && !vr.skipped()) {
                result.put("rollbackHint", "Verification failed. To restore the pre-edit state, call rollbackLocalFile.");
            }

            notifyProgressEnd(ctx, "verifyLocalProject", vr.message(), vr.verified());
            return result;
        } catch (Exception e) {
            log.error("[CodeEdit-Local] Failed to verify project: path={}", projectPath, e);
            return Map.of("success", false, "error", "Failed to verify project: " + e.getMessage(), "path", projectPath);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TerminalSession → ConnectionId mapping (remote SSH file operations)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve connectionId from terminalSessionId.
     */
    private String getConnectionId(String terminalSessionId) {
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return null;
        }
        TerminalSessionEntity entity = sshTerminalService.getTerminalSession(terminalSessionId);
        if (entity == null || !entity.isActive()) {
            log.warn("[CodeEdit] Terminal session missing or closed: {}", terminalSessionId);
            return null;
        }
        return entity.getConnectionId();
    }

    /**
     * SSH terminal session ID bound to the current run (null when no remote server is connected).
     */
    private String resolveTerminalSessionId(RunContext ctx) {
        if (!ctx.hasTerminal()) {
            log.warn("[CodeEdit] Current session has no bound SSH terminal: session={}", ctx.sessionId());
            return null;
        }
        return ctx.terminalSessionId();
    }

    /**
     * Walk up from a file path to find the project root (pom.xml / package.json / build.gradle / .git).
     */
    private String inferProjectRoot(Path filePath) {
        Path current = filePath.toAbsolutePath().normalize();
        for (int i = 0; i < 10 && current != null; i++) {
            if (java.nio.file.Files.exists(current.resolve("pom.xml"))
                    || java.nio.file.Files.exists(current.resolve("package.json"))
                    || java.nio.file.Files.exists(current.resolve("build.gradle"))
                    || java.nio.file.Files.exists(current.resolve("build.gradle.kts"))
                    || java.nio.file.Files.exists(current.resolve(".git"))) {
                return current.toString();
            }
            current = current.getParent();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Read a remote file.
     *
     * @param filePath absolute file path
     * @return file content
     */
    public Map<String, Object> readFile(RunContext ctx,
            String filePath) {

        String terminalSessionId = resolveTerminalSessionId(ctx);
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "No bound SSH terminal session; cannot read file");
        }

        try {
            log.info("[CodeEdit] Reading file: connectionId={}, path={}", connectionId, filePath);

            SshFileContentEntity entity = sshFileDomainService.content(connectionId, filePath);

            String content = entity.getContent();
            boolean truncated = false;

            if (content != null && content.length() > MAX_READ_LENGTH) {
                content = content.substring(0, MAX_READ_LENGTH);
                truncated = true;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", entity.getPath());
            result.put("name", entity.getName());
            result.put("size", entity.getSize());
            result.put("binary", entity.isBinary());
            result.put("charset", entity.getCharset());
            result.put("truncated", truncated || entity.isTruncated());
            result.put("offset", entity.getOffset());
            result.put("content", content);

            if (truncated) {
                result.put("note", "File content exceeds " + MAX_READ_LENGTH + " characters and was truncated. Use offset to read the rest.");
            }

            log.info("[CodeEdit] Read file: path={}, size={}, truncated={}",
                    filePath, entity.getSize(), truncated);

            return result;

        } catch (Exception e) {
            log.error("[CodeEdit] Failed to read file: path={}", filePath, e);
            return Map.of("success", false, "error", "Failed to read file: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * Write/overwrite a remote file.
     *
     * @param filePath absolute file path
     * @param content  file content
     * @return write result
     */
    public Map<String, Object> writeFile(RunContext ctx,
            String filePath,
            String content) {

        String terminalSessionId = resolveTerminalSessionId(ctx);
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "No bound SSH terminal session; cannot write file");
        }

        try {
            ToolExecutionPolicyGuard.requireMutationAllowed(ctx.readOnly(), "writeFile");
            log.info("[CodeEdit] Writing file: connectionId={}, path={}, contentLength={}",
                    connectionId, filePath, content != null ? content.length() : 0);

            // Create the file first if it does not exist
            try {
                sshFileDomainService.saveFile(connectionId, filePath, content != null ? content : "", false);
            } catch (Exception saveEx) {
                // File may be missing or lack permission; retry with sudo
                log.warn("[CodeEdit] Normal write failed, retrying with sudo: {}", saveEx.getMessage());
                sshFileDomainService.saveFile(connectionId, filePath, content != null ? content : "", true);
            }

            log.info("[CodeEdit] Wrote file: path={}, bytes={}", filePath, content != null ? content.length() : 0);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", filePath);
            result.put("bytesWritten", content != null ? content.length() : 0);
            result.put("message", "File written: " + filePath);

            return result;

        } catch (Exception e) {
            log.error("[CodeEdit] Failed to write file: path={}", filePath, e);
            return Map.of("success", false, "error", "Failed to write file: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * List a directory.
     *
     * @param dirPath directory path
     * @return directory listing
     */
    public Map<String, Object> listFiles(RunContext ctx,
            String dirPath) {

        String terminalSessionId = resolveTerminalSessionId(ctx);
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "No bound SSH terminal session; cannot list directory");
        }

        try {
            log.info("[CodeEdit] Listing directory: connectionId={}, path={}", connectionId, dirPath);

            SshFileTreeEntity tree = sshFileDomainService.tree(connectionId, dirPath);

            List<Map<String, Object>> items = new ArrayList<>();
            int count = 0;
            for (var item : tree.getItems()) {
                if (count >= MAX_DIR_ENTRIES) {
                    break;
                }
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("name", item.getName());
                itemMap.put("path", item.getPath());
                itemMap.put("directory", item.isDirectory());
                itemMap.put("size", item.getSize());
                items.add(itemMap);
                count++;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", tree.getCurrentPath());
            result.put("parentPath", tree.getParentPath());
            result.put("items", items);
            result.put("total", tree.getItems().size());
            result.put("truncated", tree.getItems().size() > MAX_DIR_ENTRIES);

            if (tree.getItems().size() > MAX_DIR_ENTRIES) {
                result.put("note", "Directory has more than " + MAX_DIR_ENTRIES + " entries; listing truncated");
            }

            log.info("[CodeEdit] Listed directory: path={}, items={}", dirPath, items.size());

            return result;

        } catch (Exception e) {
            log.error("[CodeEdit] Failed to list directory: path={}", dirPath, e);
            return Map.of("success", false, "error", "Failed to list directory: " + e.getMessage(), "path", dirPath);
        }
    }

    /**
     * Search remote files for a keyword.
     *
     * @param directory search directory
     * @param keyword   search keyword
     * @return search results
     */
    public Map<String, Object> searchInFiles(RunContext ctx,
            String directory,
            String keyword) {

        String terminalSessionId = resolveTerminalSessionId(ctx);

        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return Map.of("success", false, "error", "No bound SSH terminal session");
        }

        if (!sshTerminalService.sessionExists(terminalSessionId)) {
            return Map.of("success", false, "error", "SSH terminal session not found");
        }

        try {
            log.info("[CodeEdit] Searching files: dir={}, keyword={}", directory, keyword);

            // Recursive search with grep -rn
            // Escape single quotes in keyword
            String escapedKeyword = keyword.replace("'", "'\\''");
            String command = String.format("grep -rn --include='*.java' --include='*.py' --include='*.js' --include='*.ts' --include='*.tsx' --include='*.go' --include='*.rs' --include='*.c' --include='*.cpp' --include='*.h' --include='*.yml' --include='*.yaml' --include='*.json' --include='*.xml' --include='*.sh' --include='*.conf' --include='*.md' --include='*.txt' --include='*.sql' '%s' %s 2>/dev/null | head -100", escapedKeyword, directory);

            String output = sshTerminalService.executeCommand(terminalSessionId, command);

            // Parse grep output
            List<Map<String, Object>> matches = new ArrayList<>();
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.contains("Exit code:") || line.startsWith("[Exit")) {
                    continue;
                }
                // grep -rn format: file:line:content
                int firstColon = line.indexOf(':');
                int secondColon = line.indexOf(':', firstColon + 1);
                if (firstColon > 0 && secondColon > firstColon) {
                    Map<String, Object> match = new HashMap<>();
                    match.put("file", line.substring(0, firstColon));
                    match.put("line", line.substring(firstColon + 1, secondColon));
                    match.put("content", line.substring(secondColon + 1));
                    matches.add(match);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("directory", directory);
            result.put("keyword", keyword);
            result.put("matches", matches);
            result.put("total", matches.size());
            result.put("rawOutput", output.length() > 5000 ? output.substring(0, 5000) + "..." : output);

            log.info("[CodeEdit] Search complete: keyword={}, matches={}", keyword, matches.size());

            return result;

        } catch (Exception e) {
            log.error("[CodeEdit] Failed to search files: dir={}, keyword={}", directory, keyword, e);
            return Map.of("success", false, "error", "Failed to search files: " + e.getMessage());
        }
    }

    /**
     * Create a new file on the remote server.
     *
     * @param filePath file path
     * @return create result
     */
    public Map<String, Object> createFile(RunContext ctx,
            String filePath) {

        String terminalSessionId = resolveTerminalSessionId(ctx);
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "No bound SSH terminal session");
        }

        try {
            ToolExecutionPolicyGuard.requireMutationAllowed(ctx.readOnly(), "createFile");
            log.info("[CodeEdit] Creating file: connectionId={}, path={}", connectionId, filePath);

            try {
                sshFileDomainService.createFile(connectionId, filePath, false);
            } catch (Exception e) {
                log.warn("[CodeEdit] Normal create failed, retrying with sudo: {}", e.getMessage());
                sshFileDomainService.createFile(connectionId, filePath, true);
            }

            log.info("[CodeEdit] Created file: path={}", filePath);

            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "File created: " + filePath
            );

        } catch (Exception e) {
            log.error("[CodeEdit] Failed to create file: path={}", filePath, e);
            return Map.of("success", false, "error", "Failed to create file: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * Delete a remote file.
     *
     * @param filePath file path
     * @return delete result
     */
    public Map<String, Object> deleteFile(RunContext ctx,
            String filePath) {

        String terminalSessionId = resolveTerminalSessionId(ctx);
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "No bound SSH terminal session");
        }

        try {
            ToolExecutionPolicyGuard.requireMutationAllowed(ctx.readOnly(), "deleteFile");
            log.info("[CodeEdit] Deleting file: connectionId={}, path={}", connectionId, filePath);

            try {
                sshFileDomainService.delete(connectionId, filePath, false);
            } catch (Exception e) {
                log.warn("[CodeEdit] Normal delete failed, retrying with sudo: {}", e.getMessage());
                sshFileDomainService.delete(connectionId, filePath, true);
            }

            log.info("[CodeEdit] Deleted file: path={}", filePath);

            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "File deleted: " + filePath
            );

        } catch (Exception e) {
            log.error("[CodeEdit] Failed to delete file: path={}", filePath, e);
            return Map.of("success", false, "error", "Failed to delete file: " + e.getMessage(), "path", filePath);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Progress-notification helpers
    // ═══════════════════════════════════════════════════════════════

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

}
