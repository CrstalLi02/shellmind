package com.shellmind.infrastructure.agent.tool;

import com.shellmind.domain.coding.service.tool.CodeEditToolService;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.shared.model.RunContext;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ADK adapter for CodeEditToolService: declares tool parameters ({@code @Schema}),
 * loads the run context by session, then delegates to the domain service.
 * <p>
 * The method name is the tool name (ADK generates the tool description from it).
 * Keep the agent prompt in sync when renaming.
 */
@Component
public class CodeEditAdkTool {

    @Resource
    private CodeEditToolService service;

    @Resource
    private AgentRunRegistry runRegistry;

    /**
     * Read a local file.
     *
     * @param filePath absolute file path
     * @return file content
     */
    public Map<String, Object> readLocalFile(
            @Schema(name = "filePath", description = "Absolute path of the local file to read, e.g. /Users/user/project/app.js or C:\\Users\\user\\project\\app.js")
            String filePath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("readLocalFile");
        }
        return service.readLocalFile(ctx, filePath);
    }

    /**
     * Write or overwrite a local file.
     *
     * @param filePath absolute file path
     * @param content  file content
     * @return write result
     */
    public Map<String, Object> writeLocalFile(
            @Schema(name = "filePath", description = "Absolute path of the local file to write, e.g. /Users/user/project/app.js")
            String filePath,
            @Schema(name = "content", description = "Full file content (overwrites existing content)")
            String content,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("writeLocalFile");
        }
        return service.writeLocalFile(ctx, filePath, content);
    }

    /**
     * List a local directory.
     *
     * @param dirPath directory path
     * @return directory listing
     */
    public Map<String, Object> listLocalFiles(
            @Schema(name = "dirPath", description = "Local directory path to list, e.g. /Users/user/project or .")
            String dirPath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("listLocalFiles");
        }
        return service.listLocalFiles(ctx, dirPath);
    }

    /**
     * Search for a keyword in local files.
     *
     * @param directory search directory
     * @param keyword   search keyword
     * @return search results
     */
    public Map<String, Object> searchInLocalFiles(
            @Schema(name = "directory", description = "Starting directory for the search, e.g. /Users/user/project/src")
            String directory,
            @Schema(name = "keyword", description = "Search keyword or regular expression")
            String keyword,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("searchInLocalFiles");
        }
        return service.searchInLocalFiles(ctx, directory, keyword);
    }

    /**
     * Create a new local file.
     *
     * @param filePath file path
     * @return create result
     */
    public Map<String, Object> createLocalFile(
            @Schema(name = "filePath", description = "Path of the local file to create, e.g. /Users/user/project/new-app.js")
            String filePath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("createLocalFile");
        }
        return service.createLocalFile(ctx, filePath);
    }

    /**
     * Delete a local file.
     *
     * @param filePath file path
     * @return delete result
     */
    public Map<String, Object> deleteLocalFile(
            @Schema(name = "filePath", description = "Path of the local file to delete")
            String filePath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("deleteLocalFile");
        }
        return service.deleteLocalFile(ctx, filePath);
    }

    /**
     * Roll back to the backup taken before the last ShellMind write.
     */
    public Map<String, Object> rollbackLocalFile(
            @Schema(name = "filePath", description = "Path of the local file to roll back")
            String filePath,
            @Schema(name = "backupPath", description = "Backup file path returned by writeLocalFile")
            String backupPath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("rollbackLocalFile");
        }
        return service.rollbackLocalFile(ctx, filePath, backupPath);
    }

    /**
     * Call after all AI edits: detect project type, compile/verify, and return a structured result.
     */
    public Map<String, Object> verifyLocalProject(
            @Schema(name = "projectPath", description = "Absolute path of the project root")
            String projectPath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("verifyLocalProject");
        }
        return service.verifyLocalProject(ctx, projectPath);
    }

    /**
     * Read a remote file.
     *
     * @param filePath absolute file path
     * @return file content
     */
    public Map<String, Object> readFile(
            @Schema(name = "filePath", description = "Absolute path of the file to read, e.g. /etc/nginx/nginx.conf or /home/user/app.js")
            String filePath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("readFile");
        }
        return service.readFile(ctx, filePath);
    }

    /**
     * Write or overwrite a remote file.
     *
     * @param filePath absolute file path
     * @param content  file content
     * @return write result
     */
    public Map<String, Object> writeFile(
            @Schema(name = "filePath", description = "Absolute path of the file to write, e.g. /home/user/app.js")
            String filePath,
            @Schema(name = "content", description = "Full file content (overwrites existing content)")
            String content,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("writeFile");
        }
        return service.writeFile(ctx, filePath, content);
    }

    /**
     * List a directory.
     *
     * @param dirPath directory path
     * @return directory listing
     */
    public Map<String, Object> listFiles(
            @Schema(name = "dirPath", description = "Directory path to list, e.g. /home/user/project or /etc/nginx")
            String dirPath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("listFiles");
        }
        return service.listFiles(ctx, dirPath);
    }

    /**
     * Search for a keyword in remote files.
     *
     * @param directory search directory
     * @param keyword   search keyword
     * @return search results
     */
    public Map<String, Object> searchInFiles(
            @Schema(name = "directory", description = "Starting directory for the search, e.g. /home/user/project/src")
            String directory,
            @Schema(name = "keyword", description = "Search keyword or regular expression")
            String keyword,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("searchInFiles");
        }
        return service.searchInFiles(ctx, directory, keyword);
    }

    /**
     * Create a new file on the remote server.
     *
     * @param filePath file path
     * @return create result
     */
    public Map<String, Object> createFile(
            @Schema(name = "filePath", description = "Path of the file to create, e.g. /home/user/new-app.js")
            String filePath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("createFile");
        }
        return service.createFile(ctx, filePath);
    }

    /**
     * Delete a remote file.
     *
     * @param filePath file path
     * @return delete result
     */
    public Map<String, Object> deleteFile(
            @Schema(name = "filePath", description = "Path of the file to delete")
            String filePath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("deleteFile");
        }
        return service.deleteFile(ctx, filePath);
    }

    private RunContext context(ToolContext toolContext) {
        return toolContext == null ? null : runRegistry.context(toolContext.sessionId()).orElse(null);
    }

    private Map<String, Object> missingContext(String toolName) {
        return Map.of("success", false, "error", toolName + " cannot run: run context is missing (session is not running)");
    }
}
