package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH connection response DTO
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionResponseDTO {

    /** Connection ID */
    private String connectionId;

    /** Connection name */
    private String connectionName;

    /** Host address */
    private String host;

    /** Port */
    private Integer port;

    /** Username */
    private String username;

    /** Auth type: 1=password, 2=private key */
    private Integer authType;

    /** Connection status: 0=disconnected, 1=connected, 2=connecting, 3=failed */
    private Integer status;

    /** Whether encrypted */
    private Integer encrypted;

    /** User ID */
    private String userId;

    /** Created at */
    private String createdAt;

    /** Updated at */
    private String updatedAt;

}
