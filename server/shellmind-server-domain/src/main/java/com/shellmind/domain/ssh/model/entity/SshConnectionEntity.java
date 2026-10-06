package com.shellmind.domain.ssh.model.entity;

import com.shellmind.domain.ssh.model.valobj.AuthTypeEnum;
import com.shellmind.domain.ssh.model.valobj.ConnectionStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH connection config entity
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionEntity {

    private Long id;
    private String connectionId;
    private String connectionName;
    private String host;
    private Integer port;
    private String username;
    private AuthTypeEnum authType;
    private String password;
    private String privateKey;
    private Integer encrypted;
    private ConnectionStatusEnum status;
    private String userId;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;

    /**
     * Validate required fields
     */
    public void validate() {
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalArgumentException("Connection name must not be empty");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be empty");
        }
        if (port == null || port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port is invalid");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be empty");
        }
    }

}
