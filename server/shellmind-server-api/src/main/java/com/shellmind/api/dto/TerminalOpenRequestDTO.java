package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Open-terminal request
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalOpenRequestDTO {

    /** SSH connection ID */
    private String connectionId;

    /** Terminal columns */
    private Integer cols;

    /** Terminal rows */
    private Integer rows;

}
