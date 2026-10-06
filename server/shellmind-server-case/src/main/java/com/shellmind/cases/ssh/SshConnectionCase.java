package com.shellmind.cases.ssh;

import com.shellmind.api.dto.SshConnectionRequestDTO;
import com.shellmind.api.dto.SshConnectionResponseDTO;
import com.shellmind.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.shellmind.domain.ssh.model.entity.SshConnectionEntity;
import com.shellmind.domain.ssh.model.valobj.AuthTypeEnum;
import com.shellmind.domain.ssh.model.valobj.ConnectionStatusEnum;
import com.shellmind.domain.ssh.service.ISshConnectionDomainService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * SSH connection management use case. The domain throws {@link IllegalArgumentException} for invalid params.
 */
@Service
public class SshConnectionCase {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private ISshConnectionDomainService sshConnectionDomainService;

    public SshConnectionResponseDTO create(SshConnectionRequestDTO requestDTO) {
        SshConnectionEntity entity = toEntity(requestDTO);
        sshConnectionDomainService.createConnection(entity, toConfigEntity(requestDTO));
        return toResponseDTO(entity);
    }

    /**
     * Update the connection and return the full updated record
     */
    public SshConnectionResponseDTO update(SshConnectionRequestDTO requestDTO) {
        SshConnectionEntity entity = toEntity(requestDTO);
        sshConnectionDomainService.updateConnection(entity, toConfigEntity(requestDTO));
        return toResponseDTO(sshConnectionDomainService.getConnection(entity.getConnectionId()));
    }

    public void delete(String connectionId) {
        sshConnectionDomainService.deleteConnection(connectionId);
    }

    public Optional<SshConnectionResponseDTO> get(String connectionId) {
        return Optional.ofNullable(sshConnectionDomainService.getConnection(connectionId)).map(this::toResponseDTO);
    }

    /**
     * List connections and correct status from the actual SSH connection state
     */
    public List<SshConnectionResponseDTO> list(String userId) {
        return sshConnectionDomainService.getConnectionList(userId).stream()
                .map(entity -> {
                    boolean actuallyConnected = sshConnectionDomainService.isConnected(entity.getConnectionId());
                    if (actuallyConnected && entity.getStatus() != ConnectionStatusEnum.CONNECTED) {
                        entity.setStatus(ConnectionStatusEnum.CONNECTED);
                    } else if (!actuallyConnected && entity.getStatus() == ConnectionStatusEnum.CONNECTED) {
                        entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
                    }
                    return toResponseDTO(entity);
                })
                .toList();
    }

    public boolean connect(String connectionId) {
        return sshConnectionDomainService.connect(connectionId);
    }

    public void disconnect(String connectionId) {
        sshConnectionDomainService.disconnect(connectionId);
    }

    private SshConnectionEntity toEntity(SshConnectionRequestDTO dto) {
        return SshConnectionEntity.builder()
                .connectionId(dto.getConnectionId())
                .connectionName(dto.getConnectionName())
                .host(dto.getHost())
                .port(dto.getPort())
                .username(dto.getUsername())
                .authType(dto.getAuthType() != null ? AuthTypeEnum.fromCode(dto.getAuthType()) : AuthTypeEnum.PASSWORD)
                .password(dto.getPassword())
                .privateKey(dto.getPrivateKey())
                .userId(dto.getUserId())
                .build();
    }

    private SshConnectionConfigEntity toConfigEntity(SshConnectionRequestDTO dto) {
        return SshConnectionConfigEntity.builder()
                .connectTimeout(dto.getConnectTimeout())
                .keepaliveInterval(dto.getKeepaliveInterval())
                .startupCommand(dto.getStartupCommand())
                .compression(dto.getCompression())
                .strictHostKeyCheck(dto.getStrictHostKeyCheck())
                .build();
    }

    private SshConnectionResponseDTO toResponseDTO(SshConnectionEntity entity) {
        return SshConnectionResponseDTO.builder()
                .connectionId(entity.getConnectionId())
                .connectionName(entity.getConnectionName())
                .host(entity.getHost())
                .port(entity.getPort())
                .username(entity.getUsername())
                .authType(entity.getAuthType() != null ? entity.getAuthType().getCode() : null)
                .status(entity.getStatus() != null ? entity.getStatus().getCode() : null)
                .encrypted(entity.getEncrypted())
                .userId(entity.getUserId())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FMT) : null)
                .build();
    }
}
