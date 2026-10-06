package com.shellmind.infrastructure.agent.armory.node;

import com.shellmind.infrastructure.agent.model.ArmoryCommandEntity;
import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.infrastructure.agent.model.AiAgentRegisterVO;
import com.shellmind.infrastructure.agent.armory.AbstractArmorySupport;
import com.shellmind.infrastructure.agent.armory.factory.DefaultArmoryFactory;
import com.google.adk.models.springai.SpringAI;
import com.shellmind.infrastructure.agent.tool.BuildValidationAdkTool;
import com.shellmind.infrastructure.agent.tool.CodeEditAdkTool;
import com.shellmind.infrastructure.agent.tool.LocalExecuteAdkTool;
import com.shellmind.infrastructure.agent.tool.SshExecuteAdkTool;
import com.shellmind.infrastructure.agent.tool.SubAgentAdkTool;
import com.shellmind.infrastructure.agent.tool.SshTerminalToolFilter;
import com.shellmind.types.design.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;
    
    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Resource
    private LocalExecuteAdkTool localExecuteAdkTool;

    @Resource
    private CodeEditAdkTool codeEditAdkTool;

    @Resource
    private BuildValidationAdkTool buildValidationAdkTool;

    @Resource
    private SubAgentAdkTool subAgentAdkTool;

    @Resource
    private SshTerminalToolFilter sshTerminalToolFilter;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent assembly - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            LlmAgent.Builder builder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new SpringAI(chatModel, (StreamingChatModel) chatModel, chatModelConfig.getModel()))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey());

            // Build the ADK tool list
            List<Object> adkTools = new ArrayList<>();

            // Add the SSH execute tool (native ADK FunctionTool)
            try {
                log.info("Creating SSH execute tool, sshExecuteAdkTool={}", sshExecuteAdkTool);
                FunctionTool sshTool = FunctionTool.create(sshExecuteAdkTool, "executeCommand");
                log.info("FunctionTool created: name={}, declaration={}",
                        sshTool.name(),
                        sshTool.declaration().isPresent() ? sshTool.declaration().get() : "null");
                adkTools.add(sshTool);
                log.info("Registered SSH execute tool for agent [{}]", agentConfig.getName());
            } catch (Exception e) {
                log.error("Failed to create SSH ADK tool", e);
            }

            // Add the local command tool (invokes local Shell via the Tauri HTTP server)
            try {
                FunctionTool localExecTool = FunctionTool.create(localExecuteAdkTool, "executeLocalCommand");
                adkTools.add(localExecTool);
                log.info("Registered local command tool for agent [{}]", agentConfig.getName());
            } catch (Exception e) {
                log.error("Failed to create LocalExecute ADK tool", e);
            }

            // Add the AI coding toolset (remote SSH file read/write/search, etc.)
            try {
                FunctionTool readFileTool = FunctionTool.create(codeEditAdkTool, "readFile");
                FunctionTool writeFileTool = FunctionTool.create(codeEditAdkTool, "writeFile");
                FunctionTool listFilesTool = FunctionTool.create(codeEditAdkTool, "listFiles");
                FunctionTool searchTool = FunctionTool.create(codeEditAdkTool, "searchInFiles");
                FunctionTool createFileTool = FunctionTool.create(codeEditAdkTool, "createFile");
                FunctionTool deleteFileTool = FunctionTool.create(codeEditAdkTool, "deleteFile");

                adkTools.add(readFileTool);
                adkTools.add(writeFileTool);
                adkTools.add(listFilesTool);
                adkTools.add(searchTool);
                adkTools.add(createFileTool);
                adkTools.add(deleteFileTool);

                log.info("Registered remote CodeEdit tools for agent [{}] (readFile/writeFile/listFiles/searchInFiles/createFile/deleteFile)", agentConfig.getName());
            } catch (Exception e) {
                log.error("Failed to create remote CodeEdit ADK tools", e);
            }

            // Add the local file toolset (no SSH; operates on the local filesystem)
            try {
                FunctionTool readLocalFileTool = FunctionTool.create(codeEditAdkTool, "readLocalFile");
                FunctionTool writeLocalFileTool = FunctionTool.create(codeEditAdkTool, "writeLocalFile");
                FunctionTool listLocalFilesTool = FunctionTool.create(codeEditAdkTool, "listLocalFiles");
                FunctionTool searchLocalTool = FunctionTool.create(codeEditAdkTool, "searchInLocalFiles");
                FunctionTool createLocalFileTool = FunctionTool.create(codeEditAdkTool, "createLocalFile");
                FunctionTool deleteLocalFileTool = FunctionTool.create(codeEditAdkTool, "deleteLocalFile");
                FunctionTool rollbackLocalFileTool = FunctionTool.create(codeEditAdkTool, "rollbackLocalFile");
                FunctionTool verifyLocalProjectTool = FunctionTool.create(codeEditAdkTool, "verifyLocalProject");

                adkTools.add(readLocalFileTool);
                adkTools.add(writeLocalFileTool);
                adkTools.add(listLocalFilesTool);
                adkTools.add(searchLocalTool);
                adkTools.add(createLocalFileTool);
                adkTools.add(deleteLocalFileTool);
                adkTools.add(rollbackLocalFileTool);
                adkTools.add(verifyLocalProjectTool);

                log.info("Registered local CodeEdit tools for agent [{}] (readLocalFile/writeLocalFile/listLocalFiles/searchInLocalFiles/createLocalFile/deleteLocalFile/rollbackLocalFile/verifyLocalProject)", agentConfig.getName());
            } catch (Exception e) {
                log.error("Failed to create local CodeEdit ADK tools", e);
            }

            // Add compile / test / lint validation tools
            try {
                FunctionTool compileProjectTool = FunctionTool.create(buildValidationAdkTool, "compileProject");
                FunctionTool compileTestsTool = FunctionTool.create(buildValidationAdkTool, "compileTests");
                FunctionTool runUnitTestsTool = FunctionTool.create(buildValidationAdkTool, "runUnitTests");
                FunctionTool runLintTool = FunctionTool.create(buildValidationAdkTool, "runLint");

                adkTools.add(compileProjectTool);
                adkTools.add(compileTestsTool);
                adkTools.add(runUnitTestsTool);
                adkTools.add(runLintTool);

                log.info("Registered BuildValidation tools for agent [{}] (compileProject/compileTests/runUnitTests/runLint)", agentConfig.getName());
            } catch (Exception e) {
                log.error("Failed to create BuildValidation ADK tools", e);
            }

            // Add sub-agent tools (AgentTool — Explore/Verification/General)
            try {
                FunctionTool subAgentTool = subAgentAdkTool.createFunctionTool();
                adkTools.add(subAgentTool);
                // Dynamic plan-and-dispatch (merged from ShellMind 2-10): plan a DAG, then dispatch concurrently to the same sub-agents
                FunctionTool planDispatchTool = subAgentAdkTool.createPlanDispatchTool();
                adkTools.add(planDispatchTool);
                log.info("Registered sub-agent tools for agent [{}] (launchSubAgent / planAndDispatchSubAgents: Explore/Verification/General)", agentConfig.getName());
            } catch (Exception e) {
                log.error("Failed to create SubAgent ADK tools", e);
            }

            // Register tools on the agent
            if (!adkTools.isEmpty()) {
                log.info("Registering {} tools for agent [{}]", adkTools.size(), agentConfig.getName());
                builder.tools(adkTools);
            } else {
                log.warn("Agent [{}] has no tools registered", agentConfig.getName());
            }

            LlmAgent llmAgent = builder
                    // Hide all remote SSH tools when no SSH terminal is bound (stops misuse in local-only sessions)
                    .beforeModelCallback(sshTerminalToolFilter)
                    .build();
            
            // Log the agent's tool list
            log.info("Agent [{}] built, tools={}", agentConfig.getName(), llmAgent.tools());
            
            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
