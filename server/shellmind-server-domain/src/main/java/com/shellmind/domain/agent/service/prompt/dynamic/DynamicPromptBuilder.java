package com.shellmind.domain.agent.service.prompt.dynamic;

import com.shellmind.domain.conversation.model.valobj.MilestoneVO;
import com.shellmind.domain.agent.model.valobj.prompt.PromptContextVO;
import com.shellmind.domain.agent.model.valobj.prompt.TaskModeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DynamicPromptBuilder {

    public String build(String baseInstruction, PromptContextVO ctx) {
        if (ctx == null) {
            return baseInstruction;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseInstruction);

        appendEnvironmentInfo(sb, ctx);
        appendProjectInfo(sb, ctx);
        appendRecentCommands(sb, ctx);
        appendMilestones(sb, ctx);
        appendCoreMemories(sb, ctx);
        appendLongTermMemorySummary(sb, ctx);
        appendToolResultSummary(sb, ctx);
        appendTaskDescription(sb, ctx);

        String result = sb.toString();
        log.debug("Dynamic prompt built, length: {} (base: {}, dynamic: {})",
                result.length(), baseInstruction.length(), result.length() - baseInstruction.length());
        return result;
    }

    /**
     * Build dynamic context as a user-message prefix (injected into the user message).
     * Use this when the system instruction cannot be modified directly.
     * <p>
     * [FIX-20260626] Stale-context fix:
     * - System environment, SSH connection, current project → live state, inject as-is
     * - Key events, recent commands, tool summaries, core memories → from prior turns,
     *   labeled as "historical context" with a reminder to prioritize the current user
     *   message so old tasks do not mislead the new intent
     */
    public String buildMessagePrefix(PromptContextVO ctx) {
        if (ctx == null) return "";

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        if (ctx.getTaskMode() != null) {
            appendTaskMode(sb, ctx.getTaskMode());
            hasContent = true;
        }

        // ── Live state (not affected by prior conversation) ──

        if (!isEmpty(ctx.getServerInfo()) || !isEmpty(ctx.getOsInfo())
                || !isEmpty(ctx.getCurrentUser()) || !isEmpty(ctx.getCurrentDirectory())) {
            sb.append("[System Environment]\n");
            if (!isEmpty(ctx.getServerInfo()))       sb.append("Server: ").append(ctx.getServerInfo()).append("\n");
            if (!isEmpty(ctx.getOsInfo()))           sb.append("OS: ").append(ctx.getOsInfo()).append("\n");
            if (!isEmpty(ctx.getCurrentUser()))      sb.append("User: ").append(ctx.getCurrentUser()).append("\n");
            if (!isEmpty(ctx.getCurrentDirectory())) sb.append("Directory: ").append(ctx.getCurrentDirectory()).append("\n");
            hasContent = true;
        }

        // SSH connection info
        if (!isEmpty(ctx.getSshConnectionName()) || !isEmpty(ctx.getSshHost()) || !isEmpty(ctx.getSshUsername())) {
            sb.append("\n[SSH Connection]\n");
            if (!isEmpty(ctx.getSshConnectionName())) sb.append("Connection: ").append(ctx.getSshConnectionName()).append("\n");
            if (!isEmpty(ctx.getSshHost())) {
                sb.append("Host: ").append(ctx.getSshHost());
                if (ctx.getSshPort() != null) sb.append(":").append(ctx.getSshPort());
                sb.append("\n");
            }
            if (!isEmpty(ctx.getSshUsername())) sb.append("User: ").append(ctx.getSshUsername()).append("\n");
            sb.append("SSH is connected; use executeCommand to run commands\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getProjectName()) || !isEmpty(ctx.getProjectRootPath())) {
            sb.append("\n[Current Project]\n");
            if (!isEmpty(ctx.getProjectName()))     sb.append("Project: ").append(ctx.getProjectName()).append("\n");
            if (!isEmpty(ctx.getProjectRootPath())) sb.append("Project path: ").append(ctx.getProjectRootPath()).append("\n");
            hasContent = true;
        }

        // ── Task context (the referent of the current instruction; the model must use it for "these", "the above", etc.) ──
        // Note: this is not ignorable history; it is direct context for the current instruction.
        if (!isEmpty(ctx.getTaskDescription())) {
            sb.append("\n[Current Task]\n").append(ctx.getTaskDescription()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getPriorUserInstructions())) {
            sb.append("\n[Earlier user instructions in this session — when the user says \"these\", \"as above\", or \"refine them\", the referent is here and must be used]\n");
            sb.append(ctx.getPriorUserInstructions()).append("\n");
            hasContent = true;
        }

        // ── Historical context (commands/events from prior turns, labeled to avoid misleading the model) ──
        boolean hasHistoricalContext = false;

        if (ctx.getRecentCommands() != null && !ctx.getRecentCommands().isEmpty()) {
            if (!hasHistoricalContext) {
                sb.append("\n[Prior conversation — the following is from earlier turns; prioritize the current user message]\n");
                hasHistoricalContext = true;
            }
            sb.append("Recently executed commands:\n");
            for (String cmd : ctx.getRecentCommands()) {
                sb.append("- ").append(cmd).append("\n");
            }
            hasContent = true;
        }

        if (ctx.getMilestoneVOS() != null && !ctx.getMilestoneVOS().isEmpty()) {
            if (!hasHistoricalContext) {
                sb.append("\n[Prior conversation — the following is from earlier turns; prioritize the current user message]\n");
                hasHistoricalContext = true;
            }
            sb.append("Key events:\n");
            for (MilestoneVO m : ctx.getMilestoneVOS()) {
                sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
            }
            hasContent = true;
        }

        if (!isEmpty(ctx.getToolResultSummary())) {
            if (!hasHistoricalContext) {
                sb.append("\n[Prior conversation — the following is from earlier turns; prioritize the current user message]\n");
                hasHistoricalContext = true;
            }
            sb.append("Tool execution summary:\n").append(ctx.getToolResultSummary()).append("\n");
            hasContent = true;
        }

        // ── Long-term memory (persists across sessions) ──

        if (!isEmpty(ctx.getCoreMemories())) {
            sb.append("\n[Core memory — durable cross-session preferences]\n").append(ctx.getCoreMemories()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getLongTermMemorySummary())) {
            sb.append("\n[Long-term memory — historical environment/versions/troubleshooting notes; for reference only, prefer this round's tool results]\n").append(ctx.getLongTermMemorySummary()).append("\n");
            hasContent = true;
        }

        if (!hasContent) return "";

        String prefix = sb.toString();
        log.debug("Built message prefix, length: {}", prefix.length());
        return prefix;
    }

    private void appendTaskMode(StringBuilder sb, TaskModeVO taskMode) {
        sb.append("[Current-turn task mode]\n");
        switch (taskMode) {
            case EXECUTE -> sb.append("The current input is an explicit execute or continue instruction. Act on the existing analysis immediately; confirm only for irreversible operations or when required safety parameters are missing.\n");
            case STATUS -> sb.append("The current input is a status or gap check, not a change request. Read and inspect only; do not write, create, delete, or run commands with side effects. Answer completion status and remaining gaps based on evidence.\n");
            case CLARIFY -> sb.append("The current input is missing a goal or required parameters. Restate the understood goal, then ask the fewest necessary questions; do not modify files.\n");
            case CONVERSATIONAL -> sb.append("Answer the current question; modify files only when the user explicitly asks for a change.\n");
        }
    }

    public String buildOutputDiscipline() {
        return """
                [Agent Output Discipline]
                1. Before each tool call, you must first explain this action and its purpose in one short sentence (at most 30 words) in the user's language.
                2. A batch of parallel tool calls needs only one explanation; do not repeat it for later tools unless there is a new decision.
                3. Do not repeat previous plans, tool results, or conclusions.
                4. If you already have enough information, give the final reply immediately; do not make duplicate or low-value tool calls.
                5. The final reply must contain exactly one complete conclusion; do not restate process sentences, opening remarks, or tool output.
                6. Reply in the language the user writes in (e.g. Simplified Chinese for Chinese messages, English for English messages); do not mix languages within a sentence.
                7. Keep code, paths, commands, logs, dependency names, and proper nouns in their original form; write code comments in the language the project already uses.
                8. If the underlying model drifts into another language, convert the reply to the user's language before presenting it.
                """;
    }

    private void appendEnvironmentInfo(StringBuilder sb, PromptContextVO ctx) {
        boolean hasAnyEnv = !isEmpty(ctx.getServerInfo()) || !isEmpty(ctx.getOsInfo())
                || !isEmpty(ctx.getCurrentUser()) || !isEmpty(ctx.getCurrentDirectory());
        boolean hasSshConnection = !isEmpty(ctx.getSshConnectionName()) || !isEmpty(ctx.getSshHost())
                || !isEmpty(ctx.getSshUsername());
        if (!hasAnyEnv && !hasSshConnection) {
            return;
        }
        sb.append("\n\n## Current Environment\n");
        if (!isEmpty(ctx.getServerInfo()))       sb.append("- Server: ").append(ctx.getServerInfo()).append("\n");
        if (!isEmpty(ctx.getOsInfo()))           sb.append("- OS: ").append(ctx.getOsInfo()).append("\n");
        if (!isEmpty(ctx.getCurrentUser()))      sb.append("- Current user: ").append(ctx.getCurrentUser()).append("\n");
        if (!isEmpty(ctx.getCurrentDirectory())) sb.append("- Working directory: ").append(ctx.getCurrentDirectory()).append("\n");
        // SSH connection info
        if (hasSshConnection) {
            sb.append("\n### SSH Connection\n");
            if (!isEmpty(ctx.getSshConnectionName())) sb.append("- Connection: ").append(ctx.getSshConnectionName()).append("\n");
            if (!isEmpty(ctx.getSshHost()))           sb.append("- Host: ").append(ctx.getSshHost());
            if (ctx.getSshPort() != null)             sb.append(":").append(ctx.getSshPort());
            sb.append("\n");
            if (!isEmpty(ctx.getSshUsername()))       sb.append("- User: ").append(ctx.getSshUsername()).append("\n");
            sb.append("- **You are connected to this SSH server; use the executeCommand tool to run commands**\n");
        }
    }

    private void appendProjectInfo(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getProjectName()) && isEmpty(ctx.getProjectRootPath())) {
            return;
        }
        sb.append("\n\n## Current Project\n");
        if (!isEmpty(ctx.getProjectName()))     sb.append("- Project: ").append(ctx.getProjectName()).append("\n");
        if (!isEmpty(ctx.getProjectRootPath())) sb.append("- Project path: ").append(ctx.getProjectRootPath()).append("\n");
    }

    private void appendRecentCommands(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getRecentCommands() == null || ctx.getRecentCommands().isEmpty()) return;
        sb.append("\n## Recent Operations (prior conversation; prioritize the current user intent)\n");
        for (String cmd : ctx.getRecentCommands()) {
            sb.append("- ").append(cmd).append("\n");
        }
    }

    private void appendMilestones(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getMilestoneVOS() == null || ctx.getMilestoneVOS().isEmpty()) return;
        sb.append("\n## Key Events (prior conversation; prioritize the current user intent)\n");
        for (MilestoneVO m : ctx.getMilestoneVOS()) {
            sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
        }
    }

    private void appendCoreMemories(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getCoreMemories())) return;
        sb.append("\n\n## Core Memory\n");
        sb.append(ctx.getCoreMemories()).append("\n");
    }

    /**
     * Render the long-term memory summary recalled by LongTermMemoryProvider as a
     * "Long-term Memory" section so the main model can see user preferences,
     * environment info, software versions, and troubleshooting experience.
     */
    private void appendLongTermMemorySummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getLongTermMemorySummary())) return;
        sb.append("\n\n## Long-term Memory\n");
        sb.append(ctx.getLongTermMemorySummary()).append("\n");
    }

    private void appendToolResultSummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getToolResultSummary())) return;
        sb.append("\n\n## Tool Execution Summary\n");
        sb.append(ctx.getToolResultSummary()).append("\n");
    }

    private void appendTaskDescription(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getTaskDescription())) return;
        sb.append("\n\n## Current Task\n");
        sb.append(ctx.getTaskDescription()).append("\n");
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
