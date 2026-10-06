package com.shellmind.domain.agent.service.execution;

import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.domain.agent.model.valobj.execution.ExecutionPolicyVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import org.springframework.stereotype.Service;

@Service
public class ExecutionPolicyResolver {

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;
    private static final int DEFAULT_MAX_AI_RETRIES = 2;
    private static final long DEFAULT_TOOL_TIMEOUT_MS = 60_000L;
    private static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 8000;

    /**
     * Execution verbs: messages containing these words should be treated as "actually do the work"
     * even if intent classification returned CHAT/CONTINUE/UNKNOWN, and must not enter a read-only policy.
     * Examples: "improve this content", "start handling it", "please fix this", "go ahead and execute"
     * (Chinese: 「完善这些内容」「开始处理」「帮我修复」「执行吧」).
     */
    private static final java.util.regex.Pattern EXECUTION_VERB_PATTERN = java.util.regex.Pattern.compile(
            "处理|完善|修复|修改|实现|执行|编写|重构|补充|优化|开始干|动手|搞定|落实|落地|干起来|继续干|继续处理|继续完善|继续修复|"
                    // English: verb stems match inflected forms (fix/fixes/fixing, handle/handling, ...)
                    + "(?i)\\b(?:handl|process|improv|refin|fix|repair|modif|chang|implement|execut|writ|refactor|"
                    + "supplement|optimi[sz]|get started|go ahead|get it done|roll out|ship it|keep going|"
                    + "continue (?:handling|processing|improving|fixing))\\w*"
    );

    public ExecutionPolicyVO resolve(IntentResultVO intentResult,
                                     String message,
                                     AiAgentConfigTableVO.Module.ReactBudget budget) {
        // Execution verbs win: an explicit "handle/improve/fix" request never uses a read-only policy,
        // even if classification mis-tags a short phrase as CHAT/CONTINUE.
        // If a custom budget is configured, use it (the budget itself is not read-only); otherwise
        // give a medium EXECUTE budget.
        if (hasExecutionVerb(message)) {
            if (budget != null) {
                return fromBudget(budget);
            }
            return fromIntent(asExecuteIntent(intentResult), message);
        }
        if (isReadOnlyIntent(intentResult)) {
            // perRound cannot be 1: when analyzing a project the model typically calls list+read+grep in parallel;
            // perRound=1 would silently skip the rest as [PER-ROUND-OVERFLOW], which the user sees as
            // "tools keep returning nothing".
            return new ExecutionPolicyVO(12, 20, 4, 2, 30_000L, 8_000, true);
        }
        if (budget != null) {
            return fromBudget(budget);
        }
        return fromIntent(intentResult, message);
    }

    private boolean hasExecutionVerb(String message) {
        return message != null && EXECUTION_VERB_PATTERN.matcher(message).find();
    }

    private IntentResultVO asExecuteIntent(IntentResultVO intentResult) {
        // Treat execution-verb hits as EXECUTE so they get a medium execution budget
        return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.EXECUTE)
                .confidence(intentResult != null ? intentResult.getConfidence() : 0.5)
                .entities(intentResult != null && intentResult.getEntities() != null
                        ? intentResult.getEntities() : java.util.Map.of())
                .build();
    }

    private boolean isReadOnlyIntent(IntentResultVO intentResult) {
        if (intentResult == null || intentResult.getIntent() == null) {
            return false;
        }
        // Note: CONTINUE (continue the previous task) is not in this list — continuation requests need
        // real execution ability. Treating them as read-only would block write tools for
        // "start handling it" / "continue improving" style instructions.
        return switch (intentResult.getIntent()) {
            case CHAT, EXPLAIN, SEARCH -> true;
            default -> false;
        };
    }

    private ExecutionPolicyVO fromBudget(AiAgentConfigTableVO.Module.ReactBudget budget) {
        return new ExecutionPolicyVO(
                budget.getMaxSteps() != null ? budget.getMaxSteps() : DEFAULT_MAX_STEPS,
                budget.getMaxToolCalls() != null ? budget.getMaxToolCalls() : DEFAULT_MAX_TOOL_CALLS,
                budget.getMaxToolCallsPerRound() != null ? budget.getMaxToolCallsPerRound() : DEFAULT_MAX_TOOL_CALLS_PER_ROUND,
                budget.getMaxAiRetries() != null ? budget.getMaxAiRetries() : DEFAULT_MAX_AI_RETRIES,
                budget.getToolTimeoutMs() != null ? budget.getToolTimeoutMs() : DEFAULT_TOOL_TIMEOUT_MS,
                budget.getContextTokenBudget() != null && budget.getContextTokenBudget() > 0
                        ? budget.getContextTokenBudget()
                        : DEFAULT_CONTEXT_TOKEN_BUDGET,
                false
        );
    }

    private ExecutionPolicyVO fromIntent(IntentResultVO intentResult, String message) {
        IntentTypeEnumVO intent = intentResult != null && intentResult.getIntent() != null
                ? intentResult.getIntent()
                : IntentTypeEnumVO.UNKNOWN;

        return switch (intent) {
            case DIAGNOSE, CONFIGURE -> new ExecutionPolicyVO(70, 240, 30, 3, 90_000L, 12000, false);
            case DEPLOY, SECURITY, BACKUP -> new ExecutionPolicyVO(80, 280, 30, 3, 120_000L, 16000, false);
            case EXECUTE, MONITOR -> new ExecutionPolicyVO(40, 160, 24, 2, 60_000L, 10000, false);
            case SEARCH, EXPLAIN -> new ExecutionPolicyVO(30, 120, 24, 2, 45_000L, 10000, false);
            case CHAT, CONTINUE -> new ExecutionPolicyVO(20, 50, 16, 1, 25_000L, 8000, false);
            case UNKNOWN -> messageLength(message) > 200
                    ? new ExecutionPolicyVO(60, 220, 24, 2, DEFAULT_TOOL_TIMEOUT_MS, 10000, false)
                    : new ExecutionPolicyVO(DEFAULT_MAX_STEPS, DEFAULT_MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS_PER_ROUND, DEFAULT_MAX_AI_RETRIES, DEFAULT_TOOL_TIMEOUT_MS, DEFAULT_CONTEXT_TOKEN_BUDGET, false);
        };
    }

    private int messageLength(String message) {
        return message == null ? 0 : message.length();
    }
}
