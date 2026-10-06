package com.shellmind.domain.agent.service.intent.enhancer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signal extractor (aligned with ShellMind SignalExtractor.ts).
 * <p>
 * Core idea: extract structured information only; do not make decisions.
 * Extracted signals are used by IntentEnhancer to look up related context and inject it into the LLM prompt.
 *
 * @author ShellMind Teaching Edition
 * 2026/6/22
 */
@Slf4j
@Component
public class SignalExtractor {

    // ═══════════════════════════════════════════════════════════════
    //  Regex patterns
    // ═══════════════════════════════════════════════════════════════

    /** File path: src/xxx.ts, ./xxx.java, /path/to/file */
    private static final Pattern[] FILE_PATH_PATTERNS = {
            // File path with separators
            Pattern.compile("(?:^|[\\s'\"(])((\\.?\\.?/[a-zA-Z0-9_\\-./]+?\\.[a-zA-Z]{1,10}))"),
            // directory/file form
            Pattern.compile("(?:^|[\\s'\"(])([a-zA-Z0-9_\\-]+/[a-zA-Z0-9_\\-./]+\\.[a-zA-Z]{1,10})"),
            // File path inside backticks
            Pattern.compile("`([^`]*\\.[a-zA-Z]{1,10})`"),
    };

    /** Symbol name: identifier starting with uppercase (component name, class name) */
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b");

    /** JSX component tag: <ComponentName> */
    private static final Pattern JSX_PATTERN = Pattern.compile("<([A-Z][a-zA-Z0-9_]*)");

    /** Method call: UserService.login */
    private static final Pattern METHOD_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\.[a-zA-Z0-9_]+");

    /** Error patterns */
    private static final Pattern[] ERROR_PATTERNS = {
            Pattern.compile("\\b([A-Z]\\w*Error|\\w*Exception|\\w*Error)\\b"),
            Pattern.compile("\\b(TypeError|ReferenceError|SyntaxError|RuntimeError|NullPointer)\\b"),
            Pattern.compile("(undefined|null is not|cannot read prop|is not a function)", Pattern.CASE_INSENSITIVE),
    };

    /** SSH/ops commands */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "\\b(?:sudo\\s+)?(nginx|redis|mysql|docker|systemctl|apt|yum|pip|npm|mvn|java|python|node|git|curl|wget|ssh|scp|rsync|tar|gzip)\\b");

    /** API endpoint hints */
    private static final Pattern API_PATTERN = Pattern.compile(
            "\\b(?:GET|POST|PUT|DELETE|PATCH)\\s+(/[a-zA-Z0-9_/\\-{}]+)", Pattern.CASE_INSENSITIVE);

    /** Ops keywords */
    private static final Pattern OPS_KEYWORD_PATTERN = Pattern.compile(
            "(内存|磁盘|网络|端口|进程|负载|连接数|并发|延迟|超时|\\b(?:CPU|memory|disk|network|port|process|load|connections|concurrency|QPS|TPS|latency|timeout|OOM|crash|panic)\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Common non-symbol words to filter out */
    private static final Set<String> STOP_WORDS = Set.of(
            "I", "O", "If", "Do", "To", "The", "A", "An", "And", "Or", "But",
            "In", "On", "At", "By", "For", "With", "From", "Up", "Out", "Down",
            "CPU", "RAM", "GPU", "SSD", "HDD", "IP", "DNS", "URL", "URI", "SSH",
            "API", "JSON", "XML", "HTML", "CSS", "SQL", "TCP", "UDP", "HTTP", "HTTPS"
    );

    // ═══════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract structured signals from the user message.
     */
    public ExtractedSignals extract(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return ExtractedSignals.empty();
        }

        return ExtractedSignals.builder()
                .filePaths(extractFilePaths(userInput))
                .symbolNames(extractSymbolNames(userInput))
                .errorPatterns(extractErrors(userInput))
                .commandHints(extractCommandHints(userInput))
                .apiHints(extractApiHints(userInput))
                .opsKeywords(extractOpsKeywords(userInput))
                .rawInput(userInput)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Extraction methods
    // ═══════════════════════════════════════════════════════════════

    private List<String> extractFilePaths(String input) {
        Set<String> results = new HashSet<>();
        for (Pattern pattern : FILE_PATH_PATTERNS) {
            Matcher m = pattern.matcher(input);
            while (m.find()) {
                String path = m.group(1);
                if (path != null && path.length() > 2 && !path.contains(" ")) {
                    results.add(path);
                }
            }
        }
        return new ArrayList<>(results);
    }

    private List<String> extractSymbolNames(String input) {
        Set<String> results = new HashSet<>();

        // Identifier starting with uppercase
        Matcher m = SYMBOL_PATTERN.matcher(input);
        while (m.find()) {
            String name = m.group(1);
            if (name != null && name.length() > 1 && !STOP_WORDS.contains(name)) {
                results.add(name);
            }
        }

        // JSX component tags
        m = JSX_PATTERN.matcher(input);
        while (m.find()) {
            String name = m.group(1);
            if (name != null) results.add(name);
        }

        // Class name in a method call
        m = METHOD_PATTERN.matcher(input);
        while (m.find()) {
            String name = m.group(1);
            if (name != null && !STOP_WORDS.contains(name)) results.add(name);
        }

        return new ArrayList<>(results);
    }

    private List<String> extractErrors(String input) {
        Set<String> results = new HashSet<>();
        for (Pattern pattern : ERROR_PATTERNS) {
            Matcher m = pattern.matcher(input);
            while (m.find()) {
                String err = m.group(1);
                if (err != null) results.add(err);
            }
        }
        return new ArrayList<>(results);
    }

    private List<String> extractCommandHints(String input) {
        Set<String> results = new HashSet<>();
        Matcher m = COMMAND_PATTERN.matcher(input);
        while (m.find()) {
            String cmd = m.group(1);
            if (cmd != null) results.add(cmd.toLowerCase());
        }
        return new ArrayList<>(results);
    }

    private List<String> extractApiHints(String input) {
        Set<String> results = new HashSet<>();
        Matcher m = API_PATTERN.matcher(input);
        while (m.find()) {
            String api = m.group(1);
            if (api != null) results.add(api);
        }
        return new ArrayList<>(results);
    }

    private List<String> extractOpsKeywords(String input) {
        Set<String> results = new HashSet<>();
        Matcher m = OPS_KEYWORD_PATTERN.matcher(input);
        while (m.find()) {
            String kw = m.group(1);
            if (kw != null) results.add(kw.toLowerCase());
        }
        return new ArrayList<>(results);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Signal data structure
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ExtractedSignals {
        /** File paths */
        private List<String> filePaths;
        /** Symbol names (component/class/function names) */
        private List<String> symbolNames;
        /** Error information */
        private List<String> errorPatterns;
        /** Command hints (nginx, docker, git, etc.) */
        private List<String> commandHints;
        /** API endpoint hints */
        private List<String> apiHints;
        /** Ops keywords */
        private List<String> opsKeywords;
        /** Original input */
        private String rawInput;

        public static ExtractedSignals empty() {
            return ExtractedSignals.builder()
                    .filePaths(List.of())
                    .symbolNames(List.of())
                    .errorPatterns(List.of())
                    .commandHints(List.of())
                    .apiHints(List.of())
                    .opsKeywords(List.of())
                    .rawInput("")
                    .build();
        }

        public boolean hasSignals() {
            return (filePaths != null && !filePaths.isEmpty())
                    || (symbolNames != null && !symbolNames.isEmpty())
                    || (errorPatterns != null && !errorPatterns.isEmpty())
                    || (commandHints != null && !commandHints.isEmpty())
                    || (apiHints != null && !apiHints.isEmpty())
                    || (opsKeywords != null && !opsKeywords.isEmpty());
        }
    }
}
