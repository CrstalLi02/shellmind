package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH connection request DTO
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionRequestDTO {

    /** Connection ID (required when updating) */
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

    /** Password */
    private String password;

    /** Private-key content */
    private String privateKey;

    /** User ID */
    private String userId;

    // ---------- Advanced settings ----------

    /** Connect timeout (seconds) */
    private Integer connectTimeout;

    /** Keepalive interval (seconds) */
    private Integer keepaliveInterval;

    /** Startup command */
    private String startupCommand;

    /** Whether to compress */
    private Boolean compression;

    /** Strict host-key checking */
    private Boolean strictHostKeyCheck;

}
