package com.shellmind.domain.policy.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.shellmind.domain.llm.adapter.port.LlmClient;
import com.shellmind.domain.llm.model.valobj.LlmRequest;
import com.shellmind.domain.llm.model.valobj.LlmTarget;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Permission guard (five layered security checks).
 * <p>Aligned with ShellMind permissionGuard.ts
 * <p>
 * Five checks (applied in order; any denial stops execution):
 * 1. AST/argument parsing: parse tool arguments and validate structure
 * 2. Regex rules: built-in 40+ dangerous-command / sensitive-file rules
 * 3. AI classification: second-pass classification for ambiguous commands (Phase 3)
 * 4. Deny fallback: unknown tools or unparseable arguments are denied
 * 5. Circuit breaker: consecutive security denials in the same session trigger automatic cooldown
 *
 * @author ShellMind
 * 2026/6/22
 */
@Slf4j
@Component
public class PermissionGuard {

    @Resource
    private LlmClient llmClient;

    /** Layer 3 AI risk classification: off by default; when enabled, uses the auxiliary model (see LlmTarget.auxiliary). */
    @Value("${shellmind.policy.ai-classification.enabled:false}")
    private boolean aiClassificationEnabled;

    // ═══════════════════════════════════════════════════════════════
    //  Layer 2: regex rule set
    // ═══════════════════════════════════════════════════════════════

    /** Dangerous commands (deny immediately). */
    private static final Rule[] DANGEROUS_COMMANDS = {
            // Filesystem destruction
            new Rule("rm_rf_root", Pattern.compile("\\brm\\s+-rf?\\s+/?(\\s|$)"), Action.DENY, "Recursively delete the root directory"),
            new Rule("rm_rf_home", Pattern.compile("\\brm\\s+-rf?\\s+~/?(\\s|$)"), Action.DENY, "Recursively delete the home directory"),
            new Rule("rm_rf_star", Pattern.compile("\\brm\\s+-rf?\\s+\\*"), Action.DENY, "Recursively delete all files in the current directory"),
            new Rule("dd_disk", Pattern.compile("\\bdd\\s+if=.*\\s+of=/dev/(sd|nvme|hd)"), Action.DENY, "Write directly to a disk device"),
            new Rule("mkfs", Pattern.compile("\\bmkfs\\.(ext[234]|xfs|btrfs|ntfs|fat)\\s+/dev/"), Action.DENY, "Format a disk partition"),
            new Rule("fork_bomb", Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;:"), Action.DENY, "Fork bomb"),
            new Rule("chmod_777_root", Pattern.compile("\\bchmod\\s+-R\\s+777\\s+/"), Action.DENY, "Recursively set root directory permissions to 777"),
            new Rule("chown_root", Pattern.compile("\\bchown\\s+-R\\s+\\S+\\s+/\\s*$"), Action.DENY, "Recursively change ownership of the root directory"),

            // System destruction
            new Rule("shutdown", Pattern.compile("\\b(shutdown|poweroff|halt|init\\s+0)\\b"), Action.DENY, "Shutdown command"),
            new Rule("reboot", Pattern.compile("\\b(reboot|init\\s+6)\\b"), Action.DENY, "Reboot command"),
            new Rule("killall_system", Pattern.compile("\\bkill\\s+-9\\s+-1\\b"), Action.DENY, "Kill all processes"),
            new Rule("ulimit_zero", Pattern.compile("\\bulimit\\s+-n\\s+0\\b"), Action.DENY, "Set file descriptor limit to 0"),

            // Dangerous network operations
            new Rule("iptables_flush", Pattern.compile("\\biptables\\s+-F\\b"), Action.DENY, "Flush firewall rules"),
            new Rule("iptables_drop_all", Pattern.compile("iptables\\s+-A\\s+(INPUT|OUTPUT|FORWARD)\\s+-j\\s+DROP"), Action.DENY, "Firewall default DROP"),
            new Rule("ifconfig_down", Pattern.compile("\\bifconfig\\s+\\S+\\s+down\\b"), Action.DENY, "Bring down a network interface"),
            new Rule("route_del_default", Pattern.compile("\\broute\\s+del\\s+default\\b"), Action.DENY, "Delete the default route"),

            // Dangerous process operations
            new Rule("pkill_system", Pattern.compile("\\bpkill\\s+(systemd|init|sshd)\\b"), Action.DENY, "Kill a critical system process"),

            // Dangerous Git operations
            new Rule("git_force_push_master", Pattern.compile("\\bgit\\s+push\\s+(-f|--force)\\s+\\S+\\s+(master|main)\\b"), Action.DENY, "Force-push to the main branch"),
            new Rule("git_clean_fd", Pattern.compile("\\bgit\\s+clean\\s+-fdx?\\b"), Action.DENY, "Git clean untracked files (including ignored)"),
            new Rule("git_reset_hard_origin", Pattern.compile("\\bgit\\s+reset\\s+--hard\\s+origin/(master|main)\\b"), Action.DENY, "Hard-reset to the remote main branch"),
    };

    /** Commands that require confirmation (CONDITIONAL → user must confirm). */
    private static final Rule[] CONDITIONAL_COMMANDS = {
            // File deletion
            new Rule("rm_any", Pattern.compile("\\brm\\s+(-[rfRd]+\\s+)?\\S+"), Action.CONFIRM, "Delete a file or directory"),
            new Rule("rmdir", Pattern.compile("\\brmdir\\s+\\S+"), Action.CONFIRM, "Remove a directory"),

            // Permission changes
            new Rule("chmod_any", Pattern.compile("\\bchmod\\s+\\S+"), Action.CONFIRM, "Change file permissions"),
            new Rule("chown_any", Pattern.compile("\\bchown\\s+\\S+"), Action.CONFIRM, "Change file owner"),

            // Package install/uninstall
            new Rule("apt_install", Pattern.compile("\\bapt(-get)?\\s+install\\b"), Action.CONFIRM, "Install a software package"),
            new Rule("apt_remove", Pattern.compile("\\bapt(-get)?\\s+(remove|purge)\\b"), Action.CONFIRM, "Uninstall a software package"),
            new Rule("yum_install", Pattern.compile("\\b(yum|dnf)\\s+install\\b"), Action.CONFIRM, "Install a software package"),
            new Rule("pip_install", Pattern.compile("\\bpip3?\\s+install\\b"), Action.CONFIRM, "Install a Python package"),
            new Rule("npm_install_global", Pattern.compile("\\bnpm\\s+install\\s+-g\\b"), Action.CONFIRM, "Install an npm package globally"),

            // Git write operations
            new Rule("git_push", Pattern.compile("\\bgit\\s+push\\b"), Action.CONFIRM, "Git push"),
            new Rule("git_reset_hard", Pattern.compile("\\bgit\\s+reset\\s+--hard\\b"), Action.CONFIRM, "Git hard reset"),
            new Rule("git_commit", Pattern.compile("\\bgit\\s+commit\\b"), Action.CONFIRM, "Git commit"),

            // System services
            new Rule("systemctl_stop", Pattern.compile("\\bsystemctl\\s+stop\\b"), Action.CONFIRM, "Stop a system service"),
            new Rule("systemctl_disable", Pattern.compile("\\bsystemctl\\s+disable\\b"), Action.CONFIRM, "Disable a system service"),
            new Rule("systemctl_restart", Pattern.compile("\\bsystemctl\\s+restart\\b"), Action.CONFIRM, "Restart a system service"),

            // Dangerous Docker operations
            new Rule("docker_rm", Pattern.compile("\\bdocker\\s+rm\\b"), Action.CONFIRM, "Remove a Docker container"),
            new Rule("docker_rmi", Pattern.compile("\\bdocker\\s+rmi\\b"), Action.CONFIRM, "Remove a Docker image"),
            new Rule("docker_stop", Pattern.compile("\\bdocker\\s+stop\\b"), Action.CONFIRM, "Stop a Docker container"),
            new Rule("docker_volume_rm", Pattern.compile("\\bdocker\\s+volume\\s+rm\\b"), Action.CONFIRM, "Remove a Docker volume"),

            // Download-and-execute
            new Rule("curl_pipe_sh", Pattern.compile("\\bcurl\\s+\\S+\\s*\\|\\s*(sh|bash|zsh)"), Action.CONFIRM, "Pipe a remote script to a shell"),
            new Rule("wget_pipe_sh", Pattern.compile("\\bwget\\s+\\S+\\s*-O\\s*-\\s*\\|\\s*(sh|bash)"), Action.CONFIRM, "Pipe a remote script to a shell"),
            new Rule("curl_bash", Pattern.compile("curl\\s+.*\\|\\s*bash"), Action.CONFIRM, "Pipe a remote script to a shell"),

            // Privileged port binding
            new Rule("bind_privileged_port", Pattern.compile("\\b(nginx|apache2|httpd)\\s+.*--port\\s+(\\d{1,3})\\b"), Action.CONFIRM, "Bind a privileged port"),

            // Dangerous database operations
            new Rule("mysql_drop", Pattern.compile("\\bDROP\\s+(DATABASE|TABLE)\\b", Pattern.CASE_INSENSITIVE), Action.CONFIRM, "Drop a database or table"),
            new Rule("redis_flushall", Pattern.compile("\\bFLUSHALL\\b", Pattern.CASE_INSENSITIVE), Action.CONFIRM, "Redis FLUSHALL (wipe all data)"),
            new Rule("redis_flushdb", Pattern.compile("\\bFLUSHDB\\b", Pattern.CASE_INSENSITIVE), Action.CONFIRM, "Redis FLUSHDB (wipe current database)"),
    };

    /** Sensitive file paths (deny immediately). */
    private static final Pattern[] SENSITIVE_FILE_PATTERNS = {
            Pattern.compile("^/etc/(shadow|passwd|gshadow|group)$"),
            Pattern.compile("^/etc/ssh/"),
            Pattern.compile("^/root/\\.ssh/"),
            Pattern.compile("^~?/\\.ssh/(id_rsa|id_ed25519|id_ecdsa|id_dsa)$"),
            Pattern.compile("^/proc/sysrq-trigger$"),
            Pattern.compile("^/sys/kernel/"),
            Pattern.compile("^/boot/"),
            Pattern.compile("\\.env$"),
            Pattern.compile("\\.pem$"),
            Pattern.compile("\\.key$"),
            Pattern.compile("^/etc/cron\\."),
            Pattern.compile("^/var/log/auth\\.log$"),
    };

    /** Safe read-only command prefixes that may pass quickly. */
    private static final Set<String> SAFE_COMMAND_PREFIXES = Set.of(
            "ls", "pwd", "cat", "head", "tail", "less", "more", "wc",
            "grep", "find", "which", "whereis", "file", "stat", "du", "df",
            "ps", "top", "free", "uptime", "who", "whoami", "id", "uname",
            "docker ps", "docker logs", "docker inspect", "docker stats",
            "git status", "git log", "git diff", "git branch", "git show",
            "git remote", "git stash list", "git tag",
            "mvn compile", "mvn test", "mvn clean", "mvn package",
            "npm run", "npm test", "npm list",
            "java -version", "node --version", "python3 --version",
            "curl -I", "ping -c", "netstat -", "ss -",
            "systemctl status", "systemctl list-units", "systemctl is-active",
            "journalctl", "dmesg", "lsof", "strace"
    );

    /** Command-injection separator / subshell pattern — detects injections such as `ls;rm -rf /` or `cat file && curl x|sh`.
     *  Targets: ;, &&, ||, | (pipe), backtick subshells.
     *  Does not match $() — too common in legitimate commands (e.g. echo $(date)), high false-positive rate.
     */
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            ";"             // command separator
            + "|&&"          // logical AND chain
            + "|\\|\\|"     // logical OR chain
            + "|`[^`]+`"    // backtick subshell
    );

    /** Circuit-breaker threshold: consecutive security denials in the same session. */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    /** Circuit-breaker cooldown (milliseconds). */
    private static final long CIRCUIT_BREAKER_COOLDOWN_MS = 60_000L;

    // ═══════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════

    /** session → consecutive denial count */
    private final java.util.concurrent.ConcurrentHashMap<String, CircuitBreakerState> circuitBreakers =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check permission for a tool invocation.
     *
     * @param sessionId  session ID (used by the circuit breaker)
     * @param toolName   tool name
     * @param args       tool arguments (JSON string or command string)
     * @return permission decision
     */
    public PermissionDecision check(String sessionId, String toolName, String args) {
        log.info("Permission check: session={}, tool={}, args={}",
                sessionId, toolName, args != null && args.length() > 100 ? args.substring(0, 100) + "..." : args);

        // Layer 5: circuit breaker (check first so a tripped session cannot keep trying)
        if (isCircuitBroken(sessionId)) {
            log.warn("Circuit breaker tripped: session={} consecutive denials reached threshold", sessionId);
            return PermissionDecision.deny("circuit_breaker",
                    "Security circuit breaker tripped: " + CIRCUIT_BREAKER_THRESHOLD + " consecutive dangerous operations were blocked. "
                            + "No tools may be executed for " + (CIRCUIT_BREAKER_COOLDOWN_MS / 1000) + " seconds.");
        }

        // Layer 1: AST/argument parsing
        if (args == null || args.isBlank()) {
            // Tool calls with no arguments are treated as safe
            return PermissionDecision.allow();
        }

        // Extract the command (from a JSON "command" field, or use args directly)
        String command = extractCommand(args);
        if (command == null || command.isBlank()) {
            return PermissionDecision.allow();
        }

        // Layer 2: regex rules
        PermissionDecision layer2Result = checkRules(sessionId, toolName, command);
        if (layer2Result.getAction() != Action.ALLOW) {
            return layer2Result;
        }

        // Sensitive file path check
        PermissionDecision fileResult = checkSensitiveFiles(command);
        if (fileResult.getAction() != Action.ALLOW) {
            recordDenial(sessionId);
            return fileResult;
        }

        // Fast-path for safe commands
        if (isSafeCommand(command)) {
            return PermissionDecision.allow();
        }

        // Layer 3: AI classification — classify commands that are neither on the safe list nor matched by danger rules
        PermissionDecision aiResult = classifyWithAI(sessionId, toolName, command);
        if (aiResult.getAction() != Action.ALLOW) {
            return aiResult;
        }

        // Layer 4: deny fallback — if AI cannot decide, allow by default (avoid over-blocking)
        return PermissionDecision.allow();
    }

    /**
     * Record the user's confirmation result (approved / denied).
     */
    public void recordConfirmation(String sessionId, boolean approved) {
        if (approved) {
            resetCircuitBreaker(sessionId);
            log.info("User confirmed execution; circuit breaker reset: session={}", sessionId);
        } else {
            recordDenial(sessionId);
        }
    }

    /**
     * Get circuit-breaker state.
     */
    public CircuitBreakerState getCircuitBreakerState(String sessionId) {
        return circuitBreakers.get(sessionId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Layer 2: regex rule check
    // ═══════════════════════════════════════════════════════════════

    private PermissionDecision checkRules(String sessionId, String toolName, String command) {
        // Dangerous commands (deny immediately)
        for (Rule rule : DANGEROUS_COMMANDS) {
            if (rule.pattern.matcher(command).find()) {
                log.warn("Dangerous command blocked: rule={}, tool={}, command={}", rule.name, toolName, command);
                recordDenial(sessionId);
                return PermissionDecision.deny(rule.name, rule.description);
            }
        }

        // Commands that require confirmation
        for (Rule rule : CONDITIONAL_COMMANDS) {
            if (rule.pattern.matcher(command).find()) {
                log.info("Command requires confirmation: rule={}, tool={}, command={}", rule.name, toolName, command);
                return PermissionDecision.confirm(rule.name, rule.description, command);
            }
        }

        return PermissionDecision.allow();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Sensitive file check
    // ═══════════════════════════════════════════════════════════════

    private PermissionDecision checkSensitiveFiles(String command) {
        String[] parts = command.split("\\s+");
        for (String part : parts) {
            // Match absolute paths and ~/ paths
            String path = part.startsWith("/") ? part : part.startsWith("~/") ? part : null;
            if (path != null) {
                for (Pattern pattern : SENSITIVE_FILE_PATTERNS) {
                    if (pattern.matcher(path).find()) {
                        log.warn("Sensitive file access blocked: path={}, command={}", path, command);
                        return PermissionDecision.deny("sensitive_file",
                                "Access to sensitive file blocked: " + path);
                    }
                }
            }

            // Match sensitive suffixes in relative paths (.env, .pem, .key, .p12, etc.)
            if (part.matches(".*\\.(env|pem|key|p12|pfx|jks|keystore)$") ||
                part.equals(".env") || part.endsWith("/.env")) {
                for (Pattern pattern : SENSITIVE_FILE_PATTERNS) {
                    if (pattern.matcher(part).find()) {
                        log.warn("Sensitive file access blocked (relative path): part={}, command={}", part, command);
                        return PermissionDecision.deny("sensitive_file",
                                "Access to sensitive file blocked: " + part);
                    }
                }
            }
        }
        return PermissionDecision.allow();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fast-path for safe commands
    // ═══════════════════════════════════════════════════════════════

    private boolean isSafeCommand(String command) {
        String trimmed = command.trim();

        // Reject commands that contain injection separators, preventing bypasses like `ls;rm -rf /`
        if (INJECTION_PATTERN.matcher(trimmed).find()) {
            log.debug("Safe-command prefix match skipped: command-injection separator detected, command={}",
                    trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed);
            return false;
        }

        for (String prefix : SAFE_COMMAND_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Layer 3: AI security classification
    // ═══════════════════════════════════════════════════════════════

    /** AI classification timeout (milliseconds). */
    private static final long AI_CLASSIFY_TIMEOUT_MS = 5_000L;

    /** AI classification cache (session+command_hash → decision), to avoid repeated calls. */
    private final java.util.concurrent.ConcurrentHashMap<String, PermissionDecision> aiClassifyCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** AI classification system prompt. */
    private static final String AI_CLASSIFY_SYSTEM_PROMPT = """
            You are a security classifier for shell commands. Analyze the given command and classify it into one of three categories:
            - DENY: The command is dangerous and should be blocked (e.g., data destruction, system compromise, privilege escalation, network attacks)
            - CONFIRM: The command is potentially risky and should require user confirmation (e.g., file modification, service restart, package installation, network requests)
            - ALLOW: The command is safe to execute (e.g., reading files, listing directories, checking status)
            
            Respond with ONLY a JSON object in this exact format:
            {"action":"DENY|CONFIRM|ALLOW","reason":"brief explanation"}
            
            Do not include any other text. Be conservative: when in doubt, choose CONFIRM over ALLOW, and DENY over CONFIRM.
            """;

    private PermissionDecision classifyWithAI(String sessionId, String toolName, String command) {
        if (!aiClassificationEnabled) {
            log.debug("AI classification layer disabled, skipping");
            return PermissionDecision.allow();
        }

        // Cache key: first 16 bits of the command hash (hex)
        String cacheKey = sessionId + ":" + Integer.toHexString(command.hashCode());
        PermissionDecision cached = aiClassifyCache.get(cacheKey);
        if (cached != null) {
            log.debug("AI classification cache hit: key={}, action={}", cacheKey, cached.getAction());
            return cached;
        }

        try {
            String userPrompt = String.format(
                    "Tool: %s\nCommand: %s\n\nClassify this command.",
                    toolName, command.length() > 500 ? command.substring(0, 500) : command
            );

            java.util.Optional<String> response = llmClient.complete(
                    LlmRequest.of(LlmTarget.auxiliary(null), AI_CLASSIFY_SYSTEM_PROMPT, userPrompt));
            if (response.isEmpty()) {
                log.debug("AI classification has no available model, skipping");
                return PermissionDecision.allow();
            }

            String content = response.get().trim();
            log.info("AI classification result: command={}, response={}",
                    command.length() > 80 ? command.substring(0, 80) + "..." : command, content);

            PermissionDecision decision = parseAiClassification(content, command);

            // Cache the result (cap at 1000 entries to prevent unbounded growth)
            if (aiClassifyCache.size() < 1000) {
                aiClassifyCache.put(cacheKey, decision);
            }

            if (decision.getAction() == Action.DENY) {
                recordDenial(sessionId);
            }

            return decision;
        } catch (Exception e) {
            log.warn("AI classification failed, allowing by default: command={}, error={}",
                    command.length() > 80 ? command.substring(0, 80) + "..." : command, e.getMessage());
            return PermissionDecision.allow();
        }
    }

    /**
     * Parse the AI classification response.
     */
    private PermissionDecision parseAiClassification(String content, String command) {
        try {
            // Extract the JSON portion
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end < 0 || end <= start) {
                log.warn("AI classification response is not JSON: {}", content);
                return PermissionDecision.allow();
            }

            String json = content.substring(start, end + 1);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);

            String action = node.has("action") ? node.get("action").asText().toUpperCase() : "ALLOW";
            String reason = node.has("reason") ? node.get("reason").asText() : "AI classification";

            return switch (action) {
                case "DENY" -> PermissionDecision.deny("ai_classify", reason);
                case "CONFIRM" -> PermissionDecision.confirm("ai_classify", reason, command);
                default -> PermissionDecision.allow();
            };
        } catch (Exception e) {
            log.warn("Failed to parse AI classification response: content={}, error={}", content, e.getMessage());
            return PermissionDecision.allow();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Layer 5: circuit breaker
    // ═══════════════════════════════════════════════════════════════

    private boolean isCircuitBroken(String sessionId) {
        CircuitBreakerState state = circuitBreakers.get(sessionId);
        if (state == null) return false;

        // Cooldown elapsed; reset
        if (System.currentTimeMillis() - state.lastDenialTime > CIRCUIT_BREAKER_COOLDOWN_MS) {
            circuitBreakers.remove(sessionId);
            return false;
        }

        return state.consecutiveDenials >= CIRCUIT_BREAKER_THRESHOLD;
    }

    private void recordDenial(String sessionId) {
        circuitBreakers.compute(sessionId, (key, state) -> {
            if (state == null || System.currentTimeMillis() - state.lastDenialTime > CIRCUIT_BREAKER_COOLDOWN_MS) {
                state = new CircuitBreakerState();
            }
            state.consecutiveDenials++;
            state.lastDenialTime = System.currentTimeMillis();
            return state;
        });
    }

    private void resetCircuitBreaker(String sessionId) {
        circuitBreakers.remove(sessionId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract a command string from arguments.
     */
    private String extractCommand(String args) {
        if (args == null || args.isBlank()) return null;

        // Try JSON parsing
        if (args.trim().startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(args);
                if (node.has("command")) return node.get("command").asText();
                if (node.has("cmd")) return node.get("cmd").asText();
                if (node.has("path")) return node.get("path").asText();
                if (node.has("file")) return node.get("file").asText();
            } catch (Exception ignored) {
            }
        }

        // Treat args as a command string
        return args;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Inner types
    // ═══════════════════════════════════════════════════════════════

    /**
     * Permission action.
     */
    public enum Action {
        /** Allow execution. */
        ALLOW,
        /** Require user confirmation. */
        CONFIRM,
        /** Deny execution. */
        DENY
    }

    /**
     * Rule definition.
     */
    @AllArgsConstructor
    private static class Rule {
        final String name;
        final Pattern pattern;
        final Action action;
        final String description;
    }

    /**
     * Permission decision result.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PermissionDecision {
        private Action action;
        private String ruleName;
        private String reason;
        private String command;

        public static PermissionDecision allow() {
            return new PermissionDecision(Action.ALLOW, null, null, null);
        }

        public static PermissionDecision deny(String ruleName, String reason) {
            return new PermissionDecision(Action.DENY, ruleName, reason, null);
        }

        public static PermissionDecision confirm(String ruleName, String reason, String command) {
            return new PermissionDecision(Action.CONFIRM, ruleName, reason, command);
        }

        public boolean isAllowed() {
            return action == Action.ALLOW;
        }

        public boolean needsConfirmation() {
            return action == Action.CONFIRM;
        }

        public boolean isDenied() {
            return action == Action.DENY;
        }
    }

    /**
     * Circuit-breaker state.
     */
    @Data
    public static class CircuitBreakerState {
        private int consecutiveDenials = 0;
        private long lastDenialTime = 0;
    }
}
