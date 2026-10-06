package com.shellmind.api;

import com.shellmind.api.dto.SshConnectionRequestDTO;
import com.shellmind.api.dto.SshConnectionResponseDTO;
import com.shellmind.api.response.Response;

import java.util.List;

/**
 * SSH connection remote API
 *
 * @author waissh dev
 */
public interface ISshConnectionService {

    /**
     * Create an SSH connection
     */
    Response<SshConnectionResponseDTO> createConnection(SshConnectionRequestDTO requestDTO);

    /**
     * Update an SSH connection
     */
    Response<SshConnectionResponseDTO> updateConnection(SshConnectionRequestDTO requestDTO);

    /**
     * Delete an SSH connection
     */
    Response<Void> deleteConnection(String connectionId);

    /**
     * Query a single connection
     */
    Response<SshConnectionResponseDTO> getConnection(String connectionId);

    /**
     * Query all connections for a user
     */
    Response<List<SshConnectionResponseDTO>> getConnectionList(String userId);

    /**
     * Establish an SSH connection
     */
    Response<Void> connect(String connectionId);

    /**
     * Disconnect an SSH connection
     */
    Response<Void> disconnect(String connectionId);

}
