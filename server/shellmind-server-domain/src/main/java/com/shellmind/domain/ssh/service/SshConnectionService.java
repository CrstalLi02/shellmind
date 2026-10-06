package com.shellmind.domain.ssh.service;

import com.shellmind.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.shellmind.domain.ssh.adapter.port.ISshSessionPort;
import com.shellmind.domain.ssh.model.entity.SshConnectionEntity;
import com.shellmind.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.shellmind.domain.ssh.model.valobj.ConnectionStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * SSH connection domain service
 *
 * @author waissh dev
 */
@Slf4j
@Service
public class SshConnectionService implements ISshConnectionDomainService {

    private final ISshConnectionRepository repository;
    private final ISshSessionPort sshSessionService;

    public SshConnectionService(ISshConnectionRepository repository, ISshSessionPort sshSessionService) {
        this.repository = repository;
        this.sshSessionService = sshSessionService;
    }

    @Override
    public void createConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity) {
        // 1. Validate required fields
        entity.validate();

        // 2. Generate connection ID
        if (entity.getConnectionId() == null || entity.getConnectionId().isBlank()) {
            entity.setConnectionId(UUID.randomUUID().toString().replace("-", ""));
        }

        // 3. Apply defaults
        entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
        if (entity.getPort() == null) {
            entity.setPort(22);
        }
        if (entity.getEncrypted() == null) {
            entity.setEncrypted(1);
        }
        if (entity.getUserId() == null || entity.getUserId().isBlank()) {
            entity.setUserId("default");
        }

        // 4. Save the connection
        repository.saveConnection(entity);

        // 5. Save advanced config
        if (configEntity != null) {
            configEntity.setConnectionId(entity.getConnectionId());
            configEntity.withDefaults();
            repository.saveConnectionConfig(configEntity);
        }

        log.info("SSH connection created connectionId={}", entity.getConnectionId());
    }

    @Override
    public void updateConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity) {
        // 1. Validate required fields
        entity.validate();

        // 2. Ensure the connection exists and load current data
        SshConnectionEntity existing = repository.queryConnectionById(entity.getConnectionId());
        if (existing == null) {
            throw new IllegalArgumentException("Connection not found");
        }

        // 3. Keep existing password/private key when left blank
        if (entity.getPassword() == null || entity.getPassword().isEmpty()) {
            entity.setPassword(existing.getPassword());
        }
        if (entity.getPrivateKey() == null || entity.getPrivateKey().isEmpty()) {
            entity.setPrivateKey(existing.getPrivateKey());
        }
        // Keep existing encrypted flag
        if (entity.getEncrypted() == null) {
            entity.setEncrypted(existing.getEncrypted());
        }

        // 4. Update the connection
        repository.updateConnection(entity);

        // 5. Update advanced config
        if (configEntity != null) {
            configEntity.setConnectionId(entity.getConnectionId());
            repository.saveConnectionConfig(configEntity);
        }

        log.info("SSH connection updated connectionId={}", entity.getConnectionId());
    }

    @Override
    public void deleteConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("Connection ID must not be empty");
        }
        repository.deleteConnection(connectionId);
        log.info("SSH connection deleted connectionId={}", connectionId);
    }

    @Override
    public SshConnectionEntity getConnection(String connectionId) {
        return repository.queryConnectionById(connectionId);
    }

    @Override
    public List<SshConnectionEntity> getConnectionList(String userId) {
        if (userId == null || userId.isBlank()) {
            userId = "default";
        }
        return repository.queryConnectionListByUserId(userId);
    }

    @Override
    public SshConnectionConfigEntity getConnectionConfig(String connectionId) {
        return repository.queryConnectionConfigById(connectionId);
    }

    @Override
    public boolean connect(String connectionId) {
        // 1. Load connection info
        SshConnectionEntity entity = repository.queryConnectionById(connectionId);
        if (entity == null) {
            throw new IllegalArgumentException("Connection not found");
        }

        // 2. Establish the SSH connection
        boolean success = sshSessionService.connect(
                connectionId,
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.getPassword(),
                entity.getPrivateKey()
        );

        // 3. Update connection status
        entity.setStatus(success ? ConnectionStatusEnum.CONNECTED : ConnectionStatusEnum.FAILED);
        repository.updateConnection(entity);

        return success;
    }

    @Override
    public void disconnect(String connectionId) {
        // 1. Disconnect SSH
        sshSessionService.disconnect(connectionId);

        // 2. Update connection status
        SshConnectionEntity entity = repository.queryConnectionById(connectionId);
        if (entity != null) {
            entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
            repository.updateConnection(entity);
        }
    }

    @Override
    public boolean isConnected(String connectionId) {
        return sshSessionService.isConnected(connectionId);
    }

}
