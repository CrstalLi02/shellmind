package com.shellmind.domain.agent.service.execution;

import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.domain.agent.model.valobj.execution.ExecutionPolicyVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionPolicyResolverTests {

    private final ExecutionPolicyResolver resolver = new ExecutionPolicyResolver();

    @Test
    void resolveUsesAgentBudgetWhenPresent() {
        AiAgentConfigTableVO.Module.ReactBudget budget = new AiAgentConfigTableVO.Module.ReactBudget();
        budget.setMaxSteps(10);
        budget.setMaxToolCalls(20);
        budget.setMaxToolCallsPerRound(3);
        budget.setMaxAiRetries(1);
        budget.setToolTimeoutMs(5000L);
        budget.setContextTokenBudget(1000);

        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.UNKNOWN).build(),
                "any",
                budget
        );

        assertEquals(10, policy.maxSteps());
        assertEquals(20, policy.maxToolCalls());
        assertEquals(3, policy.maxToolCallsPerRound());
        assertEquals(1, policy.maxAiRetries());
        assertEquals(5000L, policy.toolTimeoutMs());
        assertEquals(1000, policy.contextTokenBudget());
        assertEquals(false, policy.readOnly());
    }

    @Test
    void resolveUnknownShortMessageUsesDefaultPolicy() {
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.UNKNOWN).build(),
                "short",
                null
        );

        assertEquals(50, policy.maxSteps());
        assertEquals(200, policy.maxToolCalls());
        assertEquals(10, policy.maxToolCallsPerRound());
        assertEquals(2, policy.maxAiRetries());
        assertEquals(60_000L, policy.toolTimeoutMs());
        assertEquals(8000, policy.contextTokenBudget());
        assertEquals(false, policy.readOnly());
    }

    @Test
    void resolveProjectUnderstandingUsesTightReadOnlyPolicy() {
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.EXPLAIN).confidence(0.95).build(),
                "what is this project",
                null
        );

        // Read-only policy must still allow analysis tasks to read files in parallel (perRound at least 4)
        assertEquals(12, policy.maxSteps());
        assertEquals(20, policy.maxToolCalls());
        assertEquals(4, policy.maxToolCallsPerRound());
        assertEquals(2, policy.maxAiRetries());
        assertEquals(30_000L, policy.toolTimeoutMs());
        assertEquals(8_000, policy.contextTokenBudget());
        assertEquals(true, policy.readOnly());
    }

    @Test
    void executionVerbEscalatesMisclassifiedShortMessageToExecute() {
        // Short phrases like "start handling it" must escalate to an executable policy even if misclassified as CHAT
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CHAT).confidence(0.6).build(),
                "start handling it",
                null
        );

        assertEquals(false, policy.readOnly());
        assertEquals(40, policy.maxSteps());
    }

    @Test
    void executionVerbEscalatesContinueIntent() {
        // "improve this content" must remain executable even if misclassified as CONTINUE
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CONTINUE).confidence(0.7).build(),
                "improve this content.",
                null
        );

        assertEquals(false, policy.readOnly());
        assertEquals(40, policy.maxSteps());
    }

    @Test
    void continueIntentIsNoLongerReadOnly() {
        // CONTINUE (continue the previous task) is a continuation of execution and must not use a read-only policy
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CONTINUE).confidence(0.9).build(),
                "continue",
                null
        );

        assertEquals(false, policy.readOnly());
        assertEquals(20, policy.maxSteps());
    }

    @Test
    void pureChatWithoutExecutionVerbStaysReadOnly() {
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CHAT).confidence(0.9).build(),
                "Hi there, nice weather today",
                null
        );

        assertEquals(true, policy.readOnly());
    }

    @Test
    void explainWithExecutionVerbEscalates() {
        // When the user explicitly asks to fix/modify, the request must be executable even if classified as EXPLAIN
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.EXPLAIN).confidence(0.8).build(),
                "help me fix this error",
                null
        );

        assertEquals(false, policy.readOnly());
    }

    @Test
    void executionVerbStillRespectsConfiguredBudget() {
        // When an execution verb hits but a custom budget is configured, use the configured budget (still not read-only)
        AiAgentConfigTableVO.Module.ReactBudget budget = new AiAgentConfigTableVO.Module.ReactBudget();
        budget.setMaxSteps(10);
        budget.setMaxToolCalls(20);
        budget.setMaxToolCallsPerRound(3);
        budget.setMaxAiRetries(1);
        budget.setToolTimeoutMs(5000L);
        budget.setContextTokenBudget(1000);

        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CHAT).confidence(0.6).build(),
                "start handling it",
                budget
        );

        assertEquals(false, policy.readOnly());
        assertEquals(10, policy.maxSteps());
    }
}
