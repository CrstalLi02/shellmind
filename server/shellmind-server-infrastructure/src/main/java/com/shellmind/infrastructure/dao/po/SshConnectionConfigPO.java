package com.shellmind.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SSH connection advanced-config persistence object.
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionConfigPO {

    private Long id;
    private String connectionId;
    private Integer connectTimeout;
    private Integer keepaliveInterval;
    private String startupCommand;
    private Integer compression;
    private Integer strictHostKeyCheck;
    private String knownHosts;
    private LocalDateTime updatedAt;

}
