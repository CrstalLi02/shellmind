package com.shellmind.api.dto;

import lombok.Data;

/**
 * Current project context
 * <p>
 * Injected by the frontend. Describes the local project the user currently has open,
 * so dynamic prompts can identify the current project instead of answering from SOUL.md defaults.
 */
@Data
public class ProjectContextDTO {

    /** Project name (folder name), e.g. "ai-mcp-gateway" */
    private String name;

    /** Project root path (absolute), e.g. "/Users/xxx/coding/ai-mcp-gateway" */
    private String rootPath;

}
