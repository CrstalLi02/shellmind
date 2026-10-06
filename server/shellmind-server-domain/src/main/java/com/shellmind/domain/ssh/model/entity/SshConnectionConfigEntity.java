package com.shellmind.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH connection advanced-config entity
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionConfigEntity {

    private Long id;
    private String connectionId;
    private Integer connectTimeout;
    private Integer keepaliveInterval;
    private String startupCommand;
    private Boolean compression;
    private Boolean strictHostKeyCheck;
    private String knownHosts;
    private java.time.LocalDateTime updatedAt;

    /**
     * Apply default values.
     */
    public SshConnectionConfigEntity withDefaults() {
        if (connectTimeout == null) connectTimeout = 10;
        if (keepaliveInterval == null) keepaliveInterval = 60;
        if (compression == null) compression = false;
        if (strictHostKeyCheck == null) strictHostKeyCheck = true;
        return this;
    }

}
