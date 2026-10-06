package com.shellmind.domain.agent.service.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationFrameworkTests {

    private final ToolTrajectoryValidator validator = new ToolTrajectoryValidator();

    @Test
    void scenario_bugFix_expectedTrajectoryPasses() {
        EvaluationScenario scenario = EvaluationScenario.builder("bugfix-01", "Fix a bug: read file then modify then verify")
                .turn(1, "UserService.findUser throws a null pointer, please fix it")
                .expectTool("readLocalFile")
                .expectTool("writeLocalFile")
                .expectTool("verifyLocalProject")
                .build();

        List<ToolTrajectoryValidator.ToolCallRecord> actual = List.of(
                call("readLocalFile", "/src/UserService.java", "success"),
                call("writeLocalFile", "/src/UserService.java", "success"),
                call("verifyLocalProject", "/src", "success")
        );

        ToolTrajectoryValidator.TrajectoryResult result = validator.validate(scenario.expectedToolSequence(), actual);
        assertTrue(result.allPassed(), "Expected all tool expectations to pass");
        assertEquals(1.0, result.score());
    }

    @Test
    void scenario_bugFix_missingVerificationFails() {
        EvaluationScenario scenario = EvaluationScenario.builder("bugfix-02", "Fix a bug but skip verification")
                .turn(1, "UserService.findUser throws a null pointer, please fix it")
                .expectTool("readLocalFile")
                .expectTool("writeLocalFile")
                .expectTool("verifyLocalProject")
                .build();

        List<ToolTrajectoryValidator.ToolCallRecord> actual = List.of(
                call("readLocalFile", "/src/UserService.java", "success"),
                call("writeLocalFile", "/src/UserService.java", "success")
        );

        ToolTrajectoryValidator.TrajectoryResult result = validator.validate(scenario.expectedToolSequence(), actual);
        assertFalse(result.allPassed(), "Missing verifyLocalProject should fail");
        assertEquals(2.0 / 3.0, result.score(), 0.01, "2/3 expectations met");
    }

    @Test
    void scenario_constraintViolationDetected() {
        EvaluationScenario scenario = EvaluationScenario.builder("constraint-01", "Do not touch pom.xml")
                .turn(1, "Optimize UserService, do not change pom.xml")
                .forbidden("pom.xml")
                .expectTool("writeLocalFile")
                .build();

        List<ToolTrajectoryValidator.ToolCallRecord> actual = List.of(
                call("writeLocalFile", "/src/UserService.java", "success"),
                call("writeLocalFile", "pom.xml", "success")
        );

        ToolTrajectoryValidator.ConstraintResult constraint = validator.validateConstraints(scenario.forbiddenPaths(), actual);
        assertFalse(constraint.satisfied(), "Modifying pom.xml should be detected as constraint violation");
        assertEquals(1, constraint.violations().size());
        assertTrue(constraint.violations().get(0).contains("pom.xml"));
    }

    @Test
    void scenario_constraintSatisfied() {
        EvaluationScenario scenario = EvaluationScenario.builder("constraint-02", "Do not touch pom.xml, only change the Service")
                .turn(1, "Optimize UserService, do not change pom.xml")
                .forbidden("pom.xml")
                .expectTool("writeLocalFile")
                .build();

        List<ToolTrajectoryValidator.ToolCallRecord> actual = List.of(
                call("writeLocalFile", "/src/UserService.java", "success"),
                call("writeLocalFile", "/src/OrderService.java", "success")
        );

        ToolTrajectoryValidator.ConstraintResult constraint = validator.validateConstraints(scenario.forbiddenPaths(), actual);
        assertTrue(constraint.satisfied(), "Not modifying pom.xml should pass");
    }

    @Test
    void scenario_rollbackAfterVerificationFailure() {
        EvaluationScenario scenario = EvaluationScenario.builder("rollback-01", "Verification failed then rollback")
                .turn(1, "Modify UserService")
                .expectTool("writeLocalFile")
                .expectTool("verifyLocalProject")
                .expectTool("rollbackLocalFile")
                .build();

        List<ToolTrajectoryValidator.ToolCallRecord> actual = List.of(
                call("writeLocalFile", "/src/UserService.java", "success"),
                call("verifyLocalProject", "/src", "error"),
                call("rollbackLocalFile", "/src/UserService.java", "success")
        );

        ToolTrajectoryValidator.TrajectoryResult result = validator.validate(scenario.expectedToolSequence(), actual);
        assertTrue(result.allPassed(), "Write → verify(fail) → rollback is the correct trajectory");
    }

    @Test
    void scenario_ambiguousRequest_shouldClarifyNotBlindEdit() {
        EvaluationScenario scenario = EvaluationScenario.builder("ambiguity-01", "Ambiguous request: should not write files directly")
                .turn(1, "Please optimize the code")
                .expectToolNever("writeLocalFile")
                .build();

        List<ToolTrajectoryValidator.ToolCallRecord> actual = List.of(
                call("readLocalFile", "/src", "success"),
                call("readLocalFile", "/src/main", "success")
        );

        ToolTrajectoryValidator.TrajectoryResult result = validator.validate(scenario.expectedToolSequence(), actual);
        assertTrue(result.allPassed(), "Agent should read files but NOT write for ambiguous request");
    }

    @Test
    void scenario_wrongFileCreation_blindWriteIsDetected() {
        EvaluationScenario scenario = EvaluationScenario.builder("ambiguity-02", "Ambiguous request: blind file writes should fail")
                .turn(1, "Please optimize the code")
                .expectToolNever("writeLocalFile")
                .build();

        List<ToolTrajectoryValidator.ToolCallRecord> actual = List.of(
                call("writeLocalFile", "/src/optimized.java", "success")
        );

        ToolTrajectoryValidator.TrajectoryResult result = validator.validate(scenario.expectedToolSequence(), actual);
        assertFalse(result.allPassed(), "Blind write without clarification should fail the 'never write' check");
    }

    @Test
    void report_combinesTrajectoryAndConstraintScores() {
        ToolTrajectoryValidator.TrajectoryResult trajectory = new ToolTrajectoryValidator.TrajectoryResult(
                2.0 / 3.0, 2, 3,
                List.of(
                        new ToolTrajectoryValidator.CheckResult("readLocalFile", true, "ok"),
                        new ToolTrajectoryValidator.CheckResult("writeLocalFile", true, "ok"),
                        new ToolTrajectoryValidator.CheckResult("verifyLocalProject", false, "expected 1-∞, actual 0")
                )
        );
        ToolTrajectoryValidator.ConstraintResult constraints = new ToolTrajectoryValidator.ConstraintResult(
                true, 1.0, List.of()
        );

        EvaluationReport report = EvaluationReport.combine("bugfix-01", "fix bug", trajectory, constraints);
        assertEquals(2.0 / 3.0 * 0.7 + 1.0 * 0.3, report.totalScore(), 0.01);
        assertEquals(1, report.failures().size());
        assertFalse(report.isPass(), "Score 0.767 < 0.8 threshold");
    }

    @Test
    void fullPipeline_multiTurnScenario() {
        EvaluationScenario scenario = EvaluationScenario.builder("multi-turn-01", "3-turn conversation: fix bug then extra requirement then verify")
                .turn(1, "UserService.findUser has a null pointer")
                .turn(2, "Also check OrderService while you are at it")
                .turn(3, "After the change please run the tests")
                .expectTool("readLocalFile", 2, 10)
                .expectTool("writeLocalFile", 1, 5)
                .expectTool("verifyLocalProject")
                .forbidden("pom.xml")
                .build();

        List<ToolTrajectoryValidator.ToolCallRecord> actual = List.of(
                call("readLocalFile", "/src/UserService.java", "success"),
                call("writeLocalFile", "/src/UserService.java", "success"),
                call("readLocalFile", "/src/OrderService.java", "success"),
                call("writeLocalFile", "/src/OrderService.java", "success"),
                call("verifyLocalProject", "/src", "success")
        );

        ToolTrajectoryValidator.TrajectoryResult trajectory = validator.validate(scenario.expectedToolSequence(), actual);
        ToolTrajectoryValidator.ConstraintResult constraint = validator.validateConstraints(scenario.forbiddenPaths(), actual);

        EvaluationReport report = EvaluationReport.combine(scenario.scenarioId(), scenario.description(), trajectory, constraint);
        assertTrue(report.isPass(), "3-turn scenario should pass: score=" + report.totalScore());
    }

    private ToolTrajectoryValidator.ToolCallRecord call(String toolName, String args, String status) {
        return new ToolTrajectoryValidator.ToolCallRecord(toolName, args, status, System.currentTimeMillis());
    }
}
