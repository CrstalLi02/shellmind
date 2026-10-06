package com.shellmind.domain.agent.service.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record EvaluationScenario(
        String scenarioId,
        String description,
        List<EvalTurn> turns,
        List<ToolExpectation> expectedToolSequence,
        Set<String> forbiddenPaths,
        String projectRoot) {

    public record EvalTurn(int turnNumber, String userMessage, String context) {}

    public record ToolExpectation(String toolName, int minCalls, int maxCalls) {
        public static ToolExpectation atLeastOnce(String toolName) {
            return new ToolExpectation(toolName, 1, Integer.MAX_VALUE);
        }

        public static ToolExpectation exactly(int count, String toolName) {
            return new ToolExpectation(toolName, count, count);
        }

        public static ToolExpectation never(String toolName) {
            return new ToolExpectation(toolName, 0, 0);
        }
    }

    public static Builder builder(String scenarioId, String description) {
        return new Builder(scenarioId, description);
    }

    public static class Builder {
        private final String scenarioId;
        private final String description;
        private final List<EvalTurn> turns = new java.util.ArrayList<>();
        private final List<ToolExpectation> toolExpectations = new java.util.ArrayList<>();
        private final Set<String> forbiddenPaths = new java.util.HashSet<>();
        private String projectRoot;

        private Builder(String scenarioId, String description) {
            this.scenarioId = scenarioId;
            this.description = description;
        }

        public Builder turn(int number, String message) {
            turns.add(new EvalTurn(number, message, null));
            return this;
        }

        public Builder turn(int number, String message, String context) {
            turns.add(new EvalTurn(number, message, context));
            return this;
        }

        public Builder expectTool(String toolName) {
            toolExpectations.add(ToolExpectation.atLeastOnce(toolName));
            return this;
        }

        public Builder expectTool(String toolName, int minCalls, int maxCalls) {
            toolExpectations.add(new ToolExpectation(toolName, minCalls, maxCalls));
            return this;
        }

        public Builder expectToolNever(String toolName) {
            toolExpectations.add(ToolExpectation.never(toolName));
            return this;
        }

        public Builder forbidden(String path) {
            forbiddenPaths.add(path);
            return this;
        }

        public Builder projectRoot(String root) {
            this.projectRoot = root;
            return this;
        }

        public EvaluationScenario build() {
            return new EvaluationScenario(scenarioId, description, turns, toolExpectations, forbiddenPaths, projectRoot);
        }
    }
}
