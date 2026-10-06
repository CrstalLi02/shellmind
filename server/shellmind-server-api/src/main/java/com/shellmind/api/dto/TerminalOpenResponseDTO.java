package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Open-terminal response
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalOpenResponseDTO {

    /** Terminal session ID */
    private String sessionId;

    /** SSH connection ID */
    private String connectionId;

    /** Initial terminal output (welcome text after connect) */
    private String initialOutput;

}
