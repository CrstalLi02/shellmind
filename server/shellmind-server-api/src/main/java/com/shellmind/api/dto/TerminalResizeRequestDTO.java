package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resize-terminal request
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalResizeRequestDTO {

    /** Terminal session ID */
    private String sessionId;

    /** New column count */
    private Integer cols;

    /** New row count */
    private Integer rows;

}
