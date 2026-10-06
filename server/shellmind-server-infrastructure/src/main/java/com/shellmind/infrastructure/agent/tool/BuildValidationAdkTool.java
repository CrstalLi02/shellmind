package com.shellmind.infrastructure.agent.tool;

import com.shellmind.domain.coding.service.tool.BuildValidationToolService;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.shared.model.RunContext;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ADK adapter for BuildValidationToolService: declares tool parameters ({@code @Schema}),
 * loads the run context by session, then delegates to the domain service.
 * <p>
 * The method name is the tool name (ADK generates the tool description from it).
 * Keep the agent prompt in sync when renaming.
 */
@Component
public class BuildValidationAdkTool {

    @Resource
    private BuildValidationToolService service;

    @Resource
    private AgentRunRegistry runRegistry;

    public Map<String, Object> compileProject(
            @Schema(name = "projectPath", description = "Project directory to compile and validate; local absolute path or remote server path")
            String projectPath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("compileProject");
        }
        return service.compileProject(ctx, projectPath);
    }

    /**
     * Compile test sources (main sources plus test sources).
     *
     * <p>Used after the AI fills in unit tests, to verify that the test code compiles.
     *
     * @param projectPath project directory
     * @return compile result (includes {@code errorSummary} with key error lines)
     */
    public Map<String, Object> compileTests(
            @Schema(name = "projectPath", description = "Project directory whose test code should be compiled")
            String projectPath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("compileTests");
        }
        return service.compileTests(ctx, projectPath);
    }

    /**
     * Run unit tests.
     *
     * <p>Can target a specific test class or run all tests, and returns a summary of failures.
     *
     * @param projectPath project directory
     * @param testClass   fully qualified test class name (optional, e.g. com.example.MyServiceTest); empty runs all tests
     * @return test result (includes {@code testFailures} with failed-case details)
     */
    public Map<String, Object> runUnitTests(
            @Schema(name = "projectPath", description = "Project directory whose tests should be run")
            String projectPath,
            @Schema(name = "testClass", description = "Fully qualified test class name (optional, e.g. com.example.MyServiceTest); empty runs all tests", optional = true)
            String testClass,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("runUnitTests");
        }
        return service.runUnitTests(ctx, projectPath, testClass);
    }

    public Map<String, Object> runLint(
            @Schema(name = "projectPath", description = "Project directory to lint; local absolute path or remote server path")
            String projectPath,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        if (ctx == null) {
            return missingContext("runLint");
        }
        return service.runLint(ctx, projectPath);
    }

    private RunContext context(ToolContext toolContext) {
        return toolContext == null ? null : runRegistry.context(toolContext.sessionId()).orElse(null);
    }

    private Map<String, Object> missingContext(String toolName) {
        return Map.of("success", false, "error", toolName + " cannot run: run context is missing (session is not running)");
    }
}
