package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bind-terminal request DTO
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BindTerminalRequestDTO {
    /**
     * Agent session ID
     */
    private String chatSessionId;

    /**
     * SSH terminal session ID
     */
    private String terminalSessionId;
}
