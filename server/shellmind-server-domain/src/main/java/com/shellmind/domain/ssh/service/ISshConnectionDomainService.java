package com.shellmind.domain.ssh.service;

import com.shellmind.domain.ssh.model.entity.SshConnectionEntity;
import com.shellmind.domain.ssh.model.entity.SshConnectionConfigEntity;

import java.util.List;

/**
 * SSH connection domain service.
 *
 * @author waissh dev
 */
public interface ISshConnectionDomainService {

    /**
     * Create an SSH connection.
     */
    void createConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity);

    /**
     * Update an SSH connection.
     */
    void updateConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity);

    /**
     * Delete an SSH connection.
     */
    void deleteConnection(String connectionId);

    /**
     * Get a single connection.
     */
    SshConnectionEntity getConnection(String connectionId);

    /**
     * List all connections for a user.
     */
    List<SshConnectionEntity> getConnectionList(String userId);

    /**
     * Get advanced config for a connection.
     */
    SshConnectionConfigEntity getConnectionConfig(String connectionId);

    /**
     * Establish an SSH connection.
     * @param connectionId connection ID
     * @return whether the connection succeeded
     */
    boolean connect(String connectionId);

    /**
     * Disconnect an SSH connection.
     * @param connectionId connection ID
     */
    void disconnect(String connectionId);

    /**
     * Check whether the connection is active.
     * @param connectionId connection ID
     * @return whether it is connected
     */
    boolean isConnected(String connectionId);

}
