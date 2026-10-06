package com.shellmind.domain.agent.model.valobj.execution;

public record ExecutionPolicyVO(
        int maxSteps,
        int maxToolCalls,
        int maxToolCallsPerRound,
        int maxAiRetries,
        long toolTimeoutMs,
        int contextTokenBudget,
        boolean readOnly
) {
}
