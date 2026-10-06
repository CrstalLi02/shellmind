package com.shellmind.domain.agent.model.valobj.prompt;

import com.shellmind.domain.conversation.model.valobj.MilestoneVO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PromptContextVO {

    private TaskModeVO taskMode;

    private String serverInfo;
    private String osInfo;
    private String currentUser;
    private String currentDirectory;

    /** Current project name (e.g. "ai-mcp-gateway") */
    private String projectName;

    /** Current project root path (e.g. "/Users/xxx/coding/ai-mcp-gateway") */
    private String projectRootPath;

    private List<String> recentCommands;

    private List<MilestoneVO> milestoneVOS;
    
    private String toolResultSummary;

    private String taskDescription;

    /** Earlier user instructions in this session (excluding the current message); the referent of "these", "as above", etc. */
    private String priorUserInstructions;

    // Core memories (injected by CoreMemoryProvider, XML format)
    private String coreMemories;

    /** Long-term memory summary (recalled by LongTermMemoryProvider: environment facts, software versions, troubleshooting cases, user preferences) */
    private String longTermMemorySummary;

    // SSH connection info (injected by TerminalStateProvider)
    /** SSH connection name */
    private String sshConnectionName;
    /** SSH host address */
    private String sshHost;
    /** SSH port */
    private Integer sshPort;
    /** SSH username */
    private String sshUsername;
    /** SSH connection ID */
    private String sshConnectionId;
}
