package com.shellmind.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SSH connection persistence object.
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionPO {

    private Long id;
    private String connectionId;
    private String connectionName;
    private String host;
    private Integer port;
    private String username;
    private Integer authType;
    private String password;
    private String privateKey;
    private Integer encrypted;
    private Integer status;
    private String userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

}
