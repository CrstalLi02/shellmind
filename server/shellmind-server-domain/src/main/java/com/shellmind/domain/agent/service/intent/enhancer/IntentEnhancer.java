package com.shellmind.domain.agent.service.intent.enhancer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * Intent enhancer (aligned with ShellMind IntentEnhancer).
 * <p>
 * Core flow:
 * 1. SignalExtractor extracts structured signals
 * 2. Look up related context from those signals (files, command history, error catalog)
 * 3. [P2-5] If signals are empty, fall back to project-file keyword search
 * 4. Build enhanced context and inject it into the LLM prompt
 * <p>
 * Relationship to IntentService:
 * - IntentService classifies intent (DIAGNOSE / CONFIGURE / DEPLOY ...)
 * - IntentEnhancer enriches context (related files, commands, error info)
 * - Together: classification decides routing; enhancement supplies context
 *
 * @author ShellMind Teaching Edition
 * 2026/6/22
 */
@Slf4j
@Component
public class IntentEnhancer {

    @Resource
    private SignalExtractor signalExtractor;

    @Resource
    private ProjectFileSearchService projectFileSearchService;

    @Resource
    private com.shellmind.domain.coding.service.RepositoryIndexService repositoryIndexService;

    /** Maximum number of context snippets */
    private static final int MAX_CONTEXT_SNIPPETS = 8;

    /** Maximum context character count */
    private static final int MAX_CONTEXT_CHARS = 3000;

    /**
     * Enhance the user message (including project-file search fallback).
     * <p>
     * Flow: extract signals → look up context → [P2-5] fall back to project-file search if signals are empty
     *
     * @param userInput       original user input
     * @param sessionId       session ID
     * @param projectRootPath project root path (used for file-search fallback)
     * @return enhancement result
     */
    public EnhanceResult enhance(String userInput, String sessionId, String projectRootPath) {
        // 1. Extract signals
        SignalExtractor.ExtractedSignals signals = signalExtractor.extract(userInput);

        if (!signals.hasSignals()) {
            // [P2-5] Fall back to project-file keyword search when no signals were extracted
            log.debug("No signals extracted, trying project-file search fallback: session={}", sessionId);
            return searchByKeywordsFallback(userInput, sessionId, projectRootPath);
        }

        log.info("Signal extraction: session={}, files={}, symbols={}, errors={}, cmds={}, api={}, ops={}",
                sessionId,
                signals.getFilePaths().size(),
                signals.getSymbolNames().size(),
                signals.getErrorPatterns().size(),
                signals.getCommandHints().size(),
                signals.getApiHints().size(),
                signals.getOpsKeywords().size());

        // 2. Build context snippets
        List<ContextSnippet> snippets = new ArrayList<>();

        // 2a. File paths → prompt the model to focus on these files
        for (String filePath : signals.getFilePaths()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("file")
                    .content("File mentioned by the user: " + filePath)
                    .relevance(0.95)
                    .build());
        }

        // 2b. Command hints → provide common command references
        for (String cmd : signals.getCommandHints()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("command")
                    .content("Detected ops tool: " + cmd + ". Common commands: " + getCommonCommands(cmd))
                    .relevance(0.8)
                    .build());
        }

        // 2c. Error patterns → provide troubleshooting suggestions
        for (String err : signals.getErrorPatterns()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("error")
                    .content("Detected error: " + err + ". Suggestion: " + getErrorSuggestion(err))
                    .relevance(0.85)
                    .build());
        }

        // 2d. Ops keywords → provide monitoring checks
        for (String ops : signals.getOpsKeywords()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("ops")
                    .content("Ops focus: " + ops + ". Suggested check: " + getOpsCheckCommand(ops))
                    .relevance(0.7)
                    .build());
        }

        // 2e. API endpoints → hint at API context
        for (String api : signals.getApiHints()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("api")
                    .content("API endpoint: " + api)
                    .relevance(0.75)
                    .build());
        }

        // 3. Build enhanced context text
        String enhancedContext = buildEnhancedContext(snippets);

        log.info("Intent enhancement complete: session={}, snippets={}, contextLength={}",
                sessionId, snippets.size(), enhancedContext.length());

        return EnhanceResult.builder()
                .originalInput(userInput)
                .signals(signals)
                .contextSnippets(snippets)
                .enhancedContext(enhancedContext)
                .searchFallback(false)
                .build();
    }

    /**
     * Original enhance method (without project-file search), kept for compatibility.
     */
    public EnhanceResult enhance(String userInput, String sessionId) {
        return enhance(userInput, sessionId, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  [P2-5] Project-file keyword search fallback
    // ═══════════════════════════════════════════════════════════════

    /**
     * When signal extraction is empty, search the project filesystem by keywords.
     * <p>
     * Typical case: user says "look at the user service" → finds UserService.java
     */
    private EnhanceResult searchByKeywordsFallback(String userInput, String sessionId, String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) {
            log.debug("Project root path is empty, cannot search files: session={}", sessionId);
            return EnhanceResult.empty(userInput);
        }

        List<String> keywords = projectFileSearchService.extractKeywordsFromInput(userInput);
        if (keywords.isEmpty()) {
            return EnhanceResult.empty(userInput);
        }

        List<com.shellmind.domain.coding.service.RepositoryIndexService.IndexedFile> searchResults =
                repositoryIndexService.recallByKeywords(projectRootPath, keywords);

        if (searchResults.isEmpty()) {
            // Fall back to raw keyword file search when the repository index has no hits
            List<ProjectFileSearchService.FileSearchResult> fallbackResults =
                    projectFileSearchService.searchByKeywords(projectRootPath, keywords);
            if (fallbackResults.isEmpty()) {
                log.debug("Repository index and file search both empty: session={}, keywords={}", sessionId, keywords);
                return EnhanceResult.empty(userInput);
            }

            List<ContextSnippet> fallbackSnippets = new ArrayList<>();
            for (ProjectFileSearchService.FileSearchResult result : fallbackResults) {
                if (fallbackSnippets.size() >= MAX_CONTEXT_SNIPPETS) break;
                StringBuilder content = new StringBuilder();
                content.append("Related file found: ").append(result.getRelativePath());
                if (result.getPreview() != null && !result.getPreview().isBlank()) {
                    content.append("\n  Preview: ").append(result.getPreview());
                }
                fallbackSnippets.add(ContextSnippet.builder()
                        .type("search_result")
                        .content(content.toString())
                        .relevance(result.getRelevance())
                        .build());
            }

            String fallbackContext = buildEnhancedContext(fallbackSnippets);
            log.info("File-search fallback complete: session={}, keywords={}, found={}, snippets={}",
                    sessionId, keywords, fallbackResults.size(), fallbackSnippets.size());

            return EnhanceResult.builder()
                    .originalInput(userInput)
                    .signals(SignalExtractor.ExtractedSignals.empty())
                    .contextSnippets(fallbackSnippets)
                    .enhancedContext(fallbackContext)
                    .searchFallback(true)
                    .build();
        }

        // Repository-index recall results
        List<ContextSnippet> snippets = new ArrayList<>();
        for (com.shellmind.domain.coding.service.RepositoryIndexService.IndexedFile result : searchResults) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            StringBuilder content = new StringBuilder();
            content.append("Recalled file: ").append(result.relativePath());
            if (!result.symbols().isEmpty()) {
                content.append("  Symbols: ").append(String.join(", ", result.symbols()));
            }
            snippets.add(ContextSnippet.builder()
                    .type("repository_recall")
                    .content(content.toString())
                    .relevance(1.0D)
                    .build());
        }

        if (snippets.isEmpty()) {
            log.debug("Project-file search had no results: session={}, keywords={}", sessionId, keywords);
            return EnhanceResult.empty(userInput);
        }
        
        String enhancedContext = buildEnhancedContext(snippets);
        log.info("Repository-index recall complete: session={}, keywords={}, found={}, snippets={}",
                sessionId, keywords, searchResults.size(), snippets.size());

        return EnhanceResult.builder()
                .originalInput(userInput)
                .signals(SignalExtractor.ExtractedSignals.empty())
                .contextSnippets(snippets)
                .enhancedContext(enhancedContext)
                .searchFallback(true)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String buildEnhancedContext(List<ContextSnippet> snippets) {
        if (snippets.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("--- Intent enhancement context ---\n");

        int totalChars = 0;
        for (ContextSnippet snippet : snippets) {
            String line = snippet.getContent() + "\n";
            if (totalChars + line.length() > MAX_CONTEXT_CHARS) break;
            sb.append(line);
            totalChars += line.length();
        }

        sb.append("--- End of enhancement context ---\n");
        return sb.toString();
    }

    private String getCommonCommands(String tool) {
        return switch (tool) {
            case "nginx" -> "nginx -t (test config), systemctl status nginx, nginx -s reload";
            case "redis" -> "redis-cli ping, redis-cli info, redis-cli monitor";
            case "mysql" -> "mysql -u root -p, SHOW PROCESSLIST, SHOW STATUS";
            case "docker" -> "docker ps, docker logs <id>, docker stats, docker inspect <id>";
            case "systemctl" -> "systemctl status <svc>, systemctl restart <svc>, journalctl -u <svc>";
            case "git" -> "git status, git log --oneline -10, git diff, git stash list";
            case "mvn" -> "mvn compile, mvn test, mvn package -DskipTests, mvn clean";
            case "npm" -> "npm run dev, npm run build, npm test, npm list";
            default -> tool + " --help";
        };
    }

    private String getErrorSuggestion(String error) {
        String lower = error.toLowerCase();
        if (lower.contains("nullpointer") || lower.contains("null")) {
            return "Check whether the object was initialized; watch Optional chaining";
        }
        if (lower.contains("typeerror") || lower.contains("type")) {
            return "Check type matching and implicit conversions";
        }
        if (lower.contains("connection") || lower.contains("timeout") || lower.contains("refused")) {
            return "Check network connectivity, firewall rules, and whether the service is running";
        }
        if (lower.contains("permission") || lower.contains("denied")) {
            return "Check file permissions, user groups, and sudo configuration";
        }
        if (lower.contains("oom") || lower.contains("outofmemory")) {
            return "Check JVM heap, container memory limits, and system memory usage";
        }
        return "Review the full error log and search for the error keywords";
    }

    private String getOpsCheckCommand(String keyword) {
        return switch (keyword) {
            case "cpu" -> "top -bn1 | head -20, mpstat 1 3";
            case "内存", "memory" -> "free -h, cat /proc/meminfo";
            case "磁盘", "disk" -> "df -h, du -sh /*";
            case "网络", "network" -> "netstat -tlnp, ss -tlnp";
            case "端口", "port" -> "lsof -i :<port>, netstat -tlnp | grep <port>";
            case "进程", "process" -> "ps aux | grep <name>, top";
            case "负载", "load" -> "uptime, cat /proc/loadavg";
            case "连接数", "connections" -> "netstat -an | wc -l, ss -s";
            case "oom" -> "dmesg | grep -i oom, journalctl -k | grep -i oom";
            default -> "Check related logs and monitoring";
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  Data structures
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EnhanceResult {
        private String originalInput;
        private SignalExtractor.ExtractedSignals signals;
        private List<ContextSnippet> contextSnippets;
        private String enhancedContext;
        /** Whether project-file search fallback was used */
        private boolean searchFallback;

        public static EnhanceResult empty(String originalInput) {
            return EnhanceResult.builder()
                    .originalInput(originalInput)
                    .signals(SignalExtractor.ExtractedSignals.empty())
                    .contextSnippets(List.of())
                    .enhancedContext("")
                    .searchFallback(false)
                    .build();
        }

        public boolean hasEnhancement() {
            return enhancedContext != null && !enhancedContext.isBlank();
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ContextSnippet {
        private String type;
        private String content;
        private double relevance;
    }
}
