package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bind-terminal response DTO
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BindTerminalResponseDTO {
    /**
     * Agent session ID
     */
    private String chatSessionId;

    /**
     * SSH terminal session ID
     */
    private String terminalSessionId;

    /**
     * Whether bound
     */
    private Boolean bound;
}
