package com.shellmind.domain.agent.service.intent.enhancer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Project-file search service.
 * <p>
 * Aligned with Android P2-5: searchByKeywords fallback.
 * <p>
 * When SignalExtractor finds no signals (no file paths, symbol names, command hints, etc.),
 * search the project filesystem with keywords from the user input as extra intent enhancement.
 * <p>
 * Search strategy:
 * 1. Extract keywords from user input (tokenization + camelCase split)
 * 2. Recursively scan filenames under the project root
 * 3. Rank by match quality (exact &gt; partial &gt; extension)
 * 4. Cap the result count to keep context short
 * <p>
 * Typical cases:
 * - User says "look at the user service" → finds UserService.java
 * - User says "config file" → finds application.yml
 * - User says "SSH related code" → finds SshExecute*.java
 *
 * @author ShellMind Teaching Edition
 * 2026/6/26
 */
@Slf4j
@Service
public class ProjectFileSearchService {

    /** Maximum number of files to return */
    private static final int MAX_RESULTS = 10;

    /** Maximum search depth */
    private static final int MAX_DEPTH = 10;

    /** Maximum preview length per file */
    private static final int MAX_PREVIEW_LENGTH = 200;

    /** Excluded directories */
    private static final List<String> EXCLUDED_DIRS = List.of(
            "node_modules", ".git", "target", "build", "dist", "__pycache__",
            ".idea", ".vscode", "vendor", ".gradle", ".mvn", ".next"
    );

    /** macOS system-protected directories (must also be excluded) */
    private static final List<String> PROTECTED_MACOS_PATHS = List.of(
            "Library/Application Support/CallHistoryTransactions",
            "Library/Application Support/CallHistoryDB",
            "Library/Messages",
            "Library/Mail",
            "Library/Safari",
            "Library/Keychains",
            "Library/Containers"
    );

    /** Excluded file extensions */
    private static final List<String> EXCLUDED_EXTENSIONS = List.of(
            ".class", ".jar", ".war", ".log", ".tmp", ".bak", ".swp", ".DS_Store",
            ".png", ".jpg", ".gif", ".svg", ".ico", ".woff", ".woff2", ".ttf",
            ".photoslibrary"  // macOS Photos Library package; access triggers a permission exception
    );

    /**
     * Search the project root for files matching the keywords.
     *
     * @param projectRootPath project root path
     * @param keywords        search keywords
     * @return matching files (sorted by relevance)
     */
    public List<FileSearchResult> searchByKeywords(String projectRootPath, List<String> keywords) {
        if (projectRootPath == null || projectRootPath.isBlank() || keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }

        Path rootPath = Paths.get(projectRootPath);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.warn("Invalid project root path: {}", projectRootPath);
            return Collections.emptyList();
        }

        log.info("Project-file search: root={}, keywords={}", projectRootPath, keywords);

        List<FileSearchResult> results = new ArrayList<>();

        // Use a custom FileVisitor instead of Files.walk() so excluded dirs are skipped in preVisitDirectory
        // and macOS SIP / permission-protected dirs are never opened inside walk
        List<Path> allFiles = new ArrayList<>();
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Decide exclusion before entering the directory to avoid permission exceptions
                    if (isExcluded(dir)) {
                        log.debug("Skipping excluded directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // Check search depth
                    int depth = rootPath.relativize(dir).getNameCount();
                    if (depth > MAX_DEPTH) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!isExcluded(file) && attrs.isRegularFile()) {
                        allFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Ignore files/directories without access permission
                    log.debug("Skipping inaccessible path: {}, reason: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Project-file search failed: root={}", projectRootPath, e);
            return Collections.emptyList();
        }

        for (Path file : allFiles) {
            try {
                String relativePath = rootPath.relativize(file).toString();
                String fileName = file.getFileName().toString();

                double relevance = calculateRelevance(fileName, relativePath, keywords);
                if (relevance > 0) {
                    String preview = readFilePreview(file);
                    results.add(FileSearchResult.builder()
                            .relativePath(relativePath)
                            .fileName(fileName)
                            .extension(getExtension(fileName))
                            .relevance(relevance)
                            .preview(preview)
                            .build());
                }
            } catch (Exception e) {
                // Ignore per-file processing errors (e.g. permissions)
                log.debug("Skipping file processing: {}, reason: {}", file, e.getMessage());
            }
        }

        // Sort by relevance and truncate to MAX_RESULTS
        results.sort(Comparator.comparingDouble(FileSearchResult::getRelevance).reversed());
        if (results.size() > MAX_RESULTS) {
            results = results.subList(0, MAX_RESULTS);
        }

        log.info("Project-file search complete: found={}, returned={}", results.size(), Math.min(results.size(), MAX_RESULTS));
        return results;
    }

    /**
     * Extract keywords from user input.
     * <p>
     * Mixed tokenization: split on whitespace/punctuation + camelCase split + lowercase English.
     */
    public List<String> extractKeywordsFromInput(String userInput) {
        if (userInput == null || userInput.isBlank()) return Collections.emptyList();

        // Split on whitespace and punctuation
        String[] tokens = userInput.split("[\\s,，。.!！?？;；:：/\\\\|()\\[\\]{}\"']+");
        List<String> keywords = new ArrayList<>();

        for (String token : tokens) {
            if (token.length() < 2) continue;
            String lower = token.toLowerCase();
            keywords.add(lower);

            // CamelCase split
            if (token.matches("[A-Z][a-z]+[A-Z][a-z]+.*")) {
                String[] parts = token.split("(?=[A-Z])");
                for (String part : parts) {
                    if (part.length() >= 2) keywords.add(part.toLowerCase());
                }
            }

            // Path split
            if (token.contains("/")) {
                String[] pathParts = token.split("/");
                for (String part : pathParts) {
                    if (part.length() >= 2) keywords.add(part.toLowerCase());
                }
            }
        }

        // Dedupe
        return keywords.stream().distinct().collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Relevance scoring
    // ═══════════════════════════════════════════════════════════════

    private double calculateRelevance(String fileName, String relativePath, List<String> keywords) {
        double score = 0;
        String lowerFileName = fileName.toLowerCase();
        String lowerPath = relativePath.toLowerCase();

        for (String keyword : keywords) {
            String lowerKw = keyword.toLowerCase();

            // Exact filename match (without extension)
            String nameNoExt = lowerFileName.replaceFirst("\\.[^.]+$", "");
            if (nameNoExt.equals(lowerKw)) {
                score += 3.0;  // highest weight
            }

            // Filename contains the keyword
            if (lowerFileName.contains(lowerKw)) {
                score += 2.0;
            }

            // Path contains the keyword (directory-name match)
            if (lowerPath.contains(lowerKw)) {
                score += 1.0;
            }

            // Keyword appears as a path segment
            String[] pathParts = lowerPath.split("/");
            for (String part : pathParts) {
                if (part.equals(lowerKw)) {
                    score += 1.5;  // exact directory-name match weighs more than a general contains
                }
            }
        }

        // Extension bonus: source files weigh more
        String ext = getExtension(fileName);
        if (List.of(".java", ".ts", ".tsx", ".js", ".jsx", ".py", ".go", ".rs", ".vue", ".yml", ".yaml", ".xml", ".sql").contains(ext)) {
            score += 0.5;
        }

        return score;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private boolean isExcluded(Path path) {
        String pathStr = path.toString();

        // Check excluded directories
        for (String dir : EXCLUDED_DIRS) {
            if (pathStr.contains("/" + dir + "/") || pathStr.contains("\\" + dir + "\\")) {
                return true;
            }
        }

        // Check macOS system-protected directories
        for (String protectedPath : PROTECTED_MACOS_PATHS) {
            if (pathStr.contains(protectedPath)) {
                return true;
            }
        }

        // Check excluded extensions
        String fileName = path.getFileName().toString();
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (fileName.endsWith(ext)) return true;
        }

        // Hidden files
        if (fileName.startsWith(".")) return true;

        return false;
    }

    private String readFilePreview(Path file) {
        try {
            String content = Files.readString(file);
            if (content.length() > MAX_PREVIEW_LENGTH) {
                // First few characters + total line count
                int lines = content.split("\n").length;
                String preview = content.substring(0, MAX_PREVIEW_LENGTH);
                return preview + "... (" + lines + " lines total)";
            }
            return content;
        } catch (IOException e) {
            return "";
        }
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex) : "";
    }

    // ═══════════════════════════════════════════════════════════════
    //  Data structures
    // ═══════════════════════════════════════════════════════════════

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class FileSearchResult {
        /** Relative path */
        private String relativePath;
        /** File name */
        private String fileName;
        /** Extension */
        private String extension;
        /** Relevance score */
        private double relevance;
        /** File-content preview */
        private String preview;
    }
}
