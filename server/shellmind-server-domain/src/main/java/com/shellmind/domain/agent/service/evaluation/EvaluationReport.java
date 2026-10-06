package com.shellmind.domain.agent.service.evaluation;

import java.util.List;

public record EvaluationReport(
        String scenarioId,
        String description,
        double trajectoryScore,
        double constraintScore,
        double totalScore,
        List<String> failures,
        List<String> warnings) {

    public static final double PASS_THRESHOLD = 0.8;

    public boolean isPass() {
        return totalScore >= PASS_THRESHOLD;
    }

    public static EvaluationReport combine(String scenarioId, String description,
                                            ToolTrajectoryValidator.TrajectoryResult trajectory,
                                            ToolTrajectoryValidator.ConstraintResult constraints) {
        List<String> failures = new java.util.ArrayList<>();
        List<String> warnings = new java.util.ArrayList<>();

        for (ToolTrajectoryValidator.CheckResult check : trajectory.checks()) {
            if (!check.passed()) {
                failures.add("TOOL: " + check.detail());
            }
        }

        if (!constraints.satisfied()) {
            for (String violation : constraints.violations()) {
                failures.add("CONSTRAINT VIOLATED: " + violation);
            }
        }

        double total = trajectory.score() * 0.7 + constraints.score() * 0.3;
        return new EvaluationReport(scenarioId, description, trajectory.score(), constraints.score(), total, failures, warnings);
    }
}
