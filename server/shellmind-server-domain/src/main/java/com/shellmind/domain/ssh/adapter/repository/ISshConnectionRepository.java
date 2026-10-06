package com.shellmind.domain.ssh.adapter.repository;

import com.shellmind.domain.ssh.model.entity.SshConnectionEntity;
import com.shellmind.domain.ssh.model.entity.SshConnectionConfigEntity;

import java.util.List;

/**
 * SSH connection repository (defined in the domain layer, implemented in infrastructure).
 *
 * @author waissh dev
 */
public interface ISshConnectionRepository {

    /**
     * Save SSH connection config.
     */
    void saveConnection(SshConnectionEntity entity);

    /**
     * Update SSH connection config.
     */
    void updateConnection(SshConnectionEntity entity);

    /**
     * Delete SSH connection config.
     */
    void deleteConnection(String connectionId);

    /**
     * Query by connection ID.
     */
    SshConnectionEntity queryConnectionById(String connectionId);

    /**
     * List all connections for a user.
     */
    List<SshConnectionEntity> queryConnectionListByUserId(String userId);

    /**
     * Save or update advanced config.
     */
    void saveConnectionConfig(SshConnectionConfigEntity entity);

    /**
     * Query advanced config by connection ID.
     */
    SshConnectionConfigEntity queryConnectionConfigById(String connectionId);

}
