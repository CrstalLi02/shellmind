package com.shellmind.domain.ssh.service.file;

import com.shellmind.domain.ssh.adapter.port.ISshFilePort;
import com.shellmind.domain.ssh.adapter.port.ISshSessionPort;
import com.shellmind.domain.ssh.model.entity.SshFileContentEntity;
import com.shellmind.domain.ssh.model.entity.SshFileTreeEntity;
import com.shellmind.domain.ssh.service.ISshFileDomainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SshFileService implements ISshFileDomainService {

    private final ISshSessionPort sshSessionPort;
    private final ISshFilePort sshFilePort;

    public SshFileService(ISshSessionPort sshSessionPort, ISshFilePort sshFilePort) {
        this.sshSessionPort = sshSessionPort;
        this.sshFilePort = sshFilePort;
    }

    @Override
    public SshFileTreeEntity tree(String connectionId, String path) throws Exception {
        validateConnection(connectionId);
        return sshFilePort.listDirectory(connectionId, path);
    }

    @Override
    public SshFileContentEntity content(String connectionId, String path) throws Exception {
        validateConnection(connectionId);
        validatePath(path);
        return sshFilePort.readFile(connectionId, path);
    }

    @Override
    public SshFileContentEntity content(String connectionId, String path, Long offset, Integer limit) throws Exception {
        validateConnection(connectionId);
        validatePath(path);
        return sshFilePort.readFile(connectionId, path, offset, limit);
    }

    @Override
    public void createFile(String connectionId, String path, boolean useSudo) throws Exception {
        validateConnection(connectionId);
        validatePath(path);
        sshFilePort.createFile(connectionId, path, useSudo);
    }

    @Override
    public void createDirectory(String connectionId, String path, boolean useSudo) throws Exception {
        validateConnection(connectionId);
        validatePath(path);
        sshFilePort.createDirectory(connectionId, path, useSudo);
    }

    @Override
    public void rename(String connectionId, String oldPath, String newPath, boolean useSudo) throws Exception {
        validateConnection(connectionId);
        validatePath(oldPath);
        validatePath(newPath);
        sshFilePort.rename(connectionId, oldPath, newPath, useSudo);
    }

    @Override
    public void delete(String connectionId, String path, boolean useSudo) throws Exception {
        validateConnection(connectionId);
        validatePath(path);
        sshFilePort.delete(connectionId, path, useSudo);
    }

    @Override
    public void saveFile(String connectionId, String path, String content, boolean useSudo) throws Exception {
        validateConnection(connectionId);
        validatePath(path);
        sshFilePort.saveFile(connectionId, path, content, useSudo);
    }

    @Override
    public void uploadFile(String connectionId, String path, java.io.InputStream inputStream) throws Exception {
        validateConnection(connectionId);
        validatePath(path);
        sshFilePort.uploadFile(connectionId, path, inputStream);
    }

    @Override
    public void downloadFile(String connectionId, String path, java.io.OutputStream outputStream) throws Exception {
        validateConnection(connectionId);
        validatePath(path);
        sshFilePort.downloadFile(connectionId, path, outputStream);
    }

    private void validateConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("Connection ID must not be empty");
        }
        if (!sshSessionPort.isConnected(connectionId)) {
            throw new IllegalStateException("SSH connection is not established; connect first");
        }
    }

    private void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be empty");
        }
    }
}
