package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Terminal-write request (raw input, sent byte-by-byte to the shell)
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalWriteRequestDTO {

    /** Terminal session ID */
    private String sessionId;

    /** Input content (raw key data) */
    private String input;

}
