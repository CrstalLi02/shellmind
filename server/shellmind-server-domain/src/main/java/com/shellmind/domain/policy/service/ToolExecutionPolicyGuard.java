package com.shellmind.domain.policy.service;


import java.util.regex.Pattern;

public final class ToolExecutionPolicyGuard {

    private static final Pattern MUTATING_COMMAND = Pattern.compile(
            "(?:^|[;&|]\\s*)(?:sudo\\s+)?(?:rm|mv|cp|mkdir|touch|tee|truncate|chmod|chown|git\\s+(?:add|commit|push|reset|clean|checkout)|npm\\s+(?:install|i|uninstall)|pnpm\\s+(?:add|remove|install)|yarn\\s+(?:add|remove|install)|mvn\\s+clean)\\b"
                    + "|(?:>>?|<<<?)\\s*[^\\s|&;]",
            Pattern.CASE_INSENSITIVE
    );

    private ToolExecutionPolicyGuard() {
    }

    public static boolean isMutationTool(String toolName) {
        String normalized = toolName == null ? "" : toolName.toLowerCase();
        return normalized.startsWith("write")
                || normalized.startsWith("create")
                || normalized.startsWith("delete")
                || normalized.startsWith("edit")
                || normalized.contains("rollback")
                || normalized.equals("codeedittool")
                || normalized.equals("applyedit");
    }

    /**
     * @param readOnly whether the current run is read-only (see RunContext#readOnly)
     */
    public static void requireMutationAllowed(boolean readOnly, String toolName) {
        if (readOnly && isMutationTool(toolName)) {
            throw new ToolPolicyViolationException(
                    "This is a read-only project-understanding request; " + toolName + " was blocked. Answer from information already read.");
        }
    }

    public static void requireCommandAllowed(boolean readOnly, String command) {
        if (readOnly && MUTATING_COMMAND.matcher(command).find()) {
            throw new ToolPolicyViolationException(
                    "This is a read-only project-understanding request; a side-effecting command was blocked.");
        }
    }

    public static class ToolPolicyViolationException extends RuntimeException {
        public ToolPolicyViolationException(String message) {
            super(message);
        }
    }
}
