package com.shellmind.domain.coding.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RepositoryIndexService {

    private static final List<String> EXCLUDED_DIRS = List.of(
            "node_modules", ".git", "target", "build", "dist", "__pycache__",
            ".idea", ".vscode", "vendor", ".gradle", ".mvn", ".next", ".shellmind"
    );

    private static final List<String> SOURCE_EXTENSIONS = List.of(
            ".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".py", ".go", ".rs"
    );

    private static final Pattern JAVA_CLASS_PATTERN =
            Pattern.compile("(?:public\\s+)?(?:abstract\\s+|final\\s+)?(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD_PATTERN =
            Pattern.compile("(?:public|private|protected)\\s+(?:static\\s+)?[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\(");
    private static final Pattern TS_EXPORT_PATTERN =
            Pattern.compile("export\\s+(?:class|function|const|interface|type)\\s+(\\w+)");

    private static final long CACHE_TTL_MS = 60_000L;
    private static final int MAX_FILES = 500;

    private final WorkspaceManager workspaceManager;
    private final Map<String, CachedIndex> cache = new ConcurrentHashMap<>();

    public RepositoryIndexService(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public RepositoryIndex buildIndex(String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) return null;

        CachedIndex cached = cache.get(projectRootPath);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.index;
        }

        try {
            Path root = Path.of(projectRootPath).toAbsolutePath().normalize();
            workspaceManager.resolve(root);

            List<IndexedFile> files = new ArrayList<>();
            String projectType = detectProjectType(root);
            List<String> dependencies = detectDependencies(root);

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (EXCLUDED_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files.size() >= MAX_FILES) return FileVisitResult.TERMINATE;
                    String name = file.getFileName() != null ? file.getFileName().toString() : "";
                    String ext = getExtension(name);
                    if (ext == null || !SOURCE_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;

                    String relativePath = root.relativize(file).toString();
                    List<String> symbols = extractSymbols(file);
                    files.add(new IndexedFile(relativePath, ext, symbols));
                    return FileVisitResult.CONTINUE;
                }
            });

            RepositoryIndex index = new RepositoryIndex(projectRootPath, projectType, dependencies, files);
            cache.put(projectRootPath, new CachedIndex(index, System.currentTimeMillis()));
            log.info("Repository index built: root={}, type={}, files={}, deps={}",
                    projectRootPath, projectType, files.size(), dependencies.size());
            return index;
        } catch (Exception e) {
            log.warn("Failed to build repository index: root={}", projectRootPath, e);
            return null;
        }
    }

    public List<IndexedFile> recallByKeywords(String projectRootPath, List<String> keywords) {
        RepositoryIndex index = buildIndex(projectRootPath);
        if (index == null || keywords == null || keywords.isEmpty()) return List.of();

        return index.files().stream()
                .filter(f -> keywords.stream().anyMatch(kw ->
                        f.relativePath().toLowerCase().contains(kw.toLowerCase())
                                || f.symbols().stream().anyMatch(s -> s.toLowerCase().contains(kw.toLowerCase()))))
                .limit(10)
                .collect(Collectors.toList());
    }

    /**
     * Call after a file write so the next index build refreshes the cache
     */
    public void invalidateCache(String projectRootPath) {
        if (projectRootPath != null) {
            cache.remove(projectRootPath);
            log.debug("Repository index cache invalidated: root={}", projectRootPath);
        }
    }

    private String detectProjectType(Path root) {
        if (Files.exists(root.resolve("pom.xml"))) return "maven";
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) return "gradle";
        if (Files.exists(root.resolve("package.json"))) return "npm";
        if (Files.exists(root.resolve("go.mod"))) return "go";
        if (Files.exists(root.resolve("Cargo.toml"))) return "rust";
        if (Files.exists(root.resolve("pyproject.toml")) || Files.exists(root.resolve("setup.py"))) return "python";
        return "unknown";
    }

    private List<String> detectDependencies(Path root) {
        List<String> deps = new ArrayList<>();
        try {
            Path pom = root.resolve("pom.xml");
            if (Files.exists(pom)) {
                String content = Files.readString(pom);
                Matcher matcher = Pattern.compile("<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>")
                        .matcher(content);
                while (matcher.find() && deps.size() < 50) {
                    deps.add(matcher.group(2));
                }
            }
            Path packageJson = root.resolve("package.json");
            if (Files.exists(packageJson)) {
                String content = Files.readString(packageJson);
                Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(content);
                while (matcher.find() && deps.size() < 50) {
                    String dep = matcher.group(1);
                    if (!dep.equals("name") && !dep.equals("version") && !dep.equals("description")
                            && !dep.equals("main") && !dep.equals("scripts") && !dep.equals("dependencies")
                            && !dep.equals("devDependencies") && !dep.equals("keywords") && !dep.equals("author")
                            && !dep.equals("license") && !dep.equals("repository")) {
                        deps.add(dep);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Dependency parsing failed: {}", e.getMessage());
        }
        return deps;
    }

    private List<String> extractSymbols(Path file) {
        List<String> symbols = new ArrayList<>();
        try {
            String content = Files.readString(file);
            String name = file.getFileName().toString();

            if (name.endsWith(".java") || name.endsWith(".kt")) {
                Matcher classMatcher = JAVA_CLASS_PATTERN.matcher(content);
                while (classMatcher.find() && symbols.size() < 20) {
                    symbols.add(classMatcher.group(1));
                }
                Matcher methodMatcher = JAVA_METHOD_PATTERN.matcher(content);
                while (methodMatcher.find() && symbols.size() < 30) {
                    symbols.add(methodMatcher.group(1));
                }
            } else if (name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js")) {
                Matcher exportMatcher = TS_EXPORT_PATTERN.matcher(content);
                while (exportMatcher.find() && symbols.size() < 30) {
                    symbols.add(exportMatcher.group(1));
                }
            }
        } catch (IOException ignored) {
        }
        return symbols;
    }

    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(dotIndex).toLowerCase() : null;
    }

    public record IndexedFile(String relativePath, String extension, List<String> symbols) {}

    public record RepositoryIndex(
            String rootPath,
            String projectType,
            List<String> dependencies,
            List<IndexedFile> files) {}

    private record CachedIndex(RepositoryIndex index, long timestamp) {}
}
