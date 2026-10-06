package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Execute-command request
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalExecRequestDTO {

    /** Terminal session ID */
    private String sessionId;

    /** Command content */
    private String command;

}
