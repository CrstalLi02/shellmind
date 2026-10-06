package com.shellmind.api.dto;

import lombok.Data;

/**
 * Agent config response object
 *
 * @author xiaofuge bugstack.cn
 * 2026/1/20 08:18
 */
@Data
public class AiAgentConfigResponseDTO {

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Agent name
     */
    private String agentName;

    /**
     * Agent description
     */
    private String agentDesc;

}
