package com.shellmind.domain.agent.service.evaluation;

import com.shellmind.domain.agent.service.evaluation.EvaluationScenario.ToolExpectation;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class ToolTrajectoryValidator {

    public TrajectoryResult validate(
            List<ToolExpectation> expectations,
            List<ToolCallRecord> actualCalls) {

        List<CheckResult> checks = new ArrayList<>();
        int passed = 0;

        for (ToolExpectation expectation : expectations) {
            long actualCount = actualCalls.stream()
                    .filter(call -> call.toolName().equals(expectation.toolName()))
                    .count();

            boolean ok = actualCount >= expectation.minCalls() && actualCount <= expectation.maxCalls();
            String detail = String.format("%s: expected %d-%d, actual %d",
                    expectation.toolName(), expectation.minCalls(), expectation.maxCalls(), actualCount);
            checks.add(new CheckResult(expectation.toolName(), ok, detail));
            if (ok) passed++;
        }

        double score = expectations.isEmpty() ? 1.0 : (double) passed / expectations.size();
        return new TrajectoryResult(score, passed, expectations.size(), checks);
    }

    public ConstraintResult validateConstraints(
            java.util.Set<String> forbiddenPaths,
            List<ToolCallRecord> actualCalls) {

        if (forbiddenPaths == null || forbiddenPaths.isEmpty()) {
            return new ConstraintResult(true, 1.0, List.of());
        }

        List<String> violations = new ArrayList<>();
        for (ToolCallRecord call : actualCalls) {
            if (isPathMutationTool(call.toolName())) {
                for (String forbidden : forbiddenPaths) {
                    if (call.args() != null && call.args().contains(forbidden)) {
                        violations.add(forbidden + " (tool=" + call.toolName() + ")");
                    }
                }
            }
        }

        double score = violations.isEmpty() ? 1.0 : 0.0;
        return new ConstraintResult(violations.isEmpty(), score, violations);
    }

    private boolean isPathMutationTool(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "writeLocalFile", "createLocalFile", "deleteLocalFile", "rollbackLocalFile",
                 "writeFile", "createFile", "deleteFile", "CodeEditTool", "CodeEdit", "applyEdit" -> true;
            default -> false;
        };
    }

    public record ToolCallRecord(String toolName, String args, String status, long timestamp) {}

    public record CheckResult(String toolName, boolean passed, String detail) {}

    public record TrajectoryResult(double score, int passed, int total, List<CheckResult> checks) {
        public boolean allPassed() { return passed == total; }
    }

    public record ConstraintResult(boolean satisfied, double score, List<String> violations) {}
}
