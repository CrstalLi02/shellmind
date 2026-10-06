package com.shellmind.infrastructure.agent.tool;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.agent.service.subagent.SubAgentToolService;
import com.shellmind.domain.shared.model.RunContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * ADK adapter for SubAgentToolService: launchSubAgent / planAndDispatchSubAgents.
 */
@Component
public class SubAgentAdkTool {

    private static final String MISSING_CONTEXT = "Error: sub-agent context is not set";

    @Resource
    private SubAgentToolService service;

    @Resource
    private AgentRunRegistry runRegistry;

    /**
     * Launch a sub-agent for a complex task.
     *
     * @param subagentType agent type: Explore / Verification / General
     * @param prompt       task description
     * @param name         short identifier (optional)
     * @return execution result
     */
    public String launchSubAgent(String subagentType, String prompt, String name, ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        return ctx == null ? MISSING_CONTEXT : service.launchSubAgent(ctx, subagentType, prompt, name);
    }

    @Schema(name = "planAndDispatchSubAgents",
            description = "Plan and dispatch a complex task: split it into dependent subtasks, then run them in parallel or sequence via EXPLORE/VERIFICATION/GENERAL sub-agents and summarize the results. "
                    + "Use only when the task has several independent checks or needs multiple roles; for simple tasks, use other tools or launchSubAgent.")
    public String planAndDispatchSubAgents(
            @Schema(name = "request", description = "Full task description. Sub-agents cannot see the parent conversation, so include the goal, scope, and constraints.")
            String request,
            ToolContext toolContext) {
        RunContext ctx = context(toolContext);
        return ctx == null ? MISSING_CONTEXT : service.planAndDispatchSubAgents(ctx, request);
    }

    /** Create the launchSubAgent tool. */
    public FunctionTool createFunctionTool() {
        return FunctionTool.create(this, "launchSubAgent");
    }

    /** Create the planAndDispatchSubAgents tool. */
    public FunctionTool createPlanDispatchTool() {
        return FunctionTool.create(this, "planAndDispatchSubAgents");
    }

    private RunContext context(ToolContext toolContext) {
        return toolContext == null ? null : runRegistry.context(toolContext.sessionId()).orElse(null);
    }
}
