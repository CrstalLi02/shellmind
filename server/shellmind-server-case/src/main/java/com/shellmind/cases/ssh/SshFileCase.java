package com.shellmind.cases.ssh;

import com.shellmind.api.dto.SshFileContentResponseDTO;
import com.shellmind.api.dto.SshFileEntryDTO;
import com.shellmind.api.dto.SshFileTreeResponseDTO;
import com.shellmind.domain.ssh.model.entity.SshFileContentEntity;
import com.shellmind.domain.ssh.model.entity.SshFileEntryEntity;
import com.shellmind.domain.ssh.model.entity.SshFileTreeEntity;
import com.shellmind.domain.ssh.service.ISshFileDomainService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Remote file management use case. Throws {@link IllegalArgumentException} / {@link IllegalStateException} for invalid params or state.
 */
@Service
public class SshFileCase {

    @Resource
    private ISshFileDomainService sshFileDomainService;

    public SshFileTreeResponseDTO tree(String connectionId, String path) throws Exception {
        return toTreeDTO(sshFileDomainService.tree(connectionId, path));
    }

    public SshFileContentResponseDTO content(String connectionId, String path) throws Exception {
        return toContentDTO(sshFileDomainService.content(connectionId, path));
    }

    public SshFileContentResponseDTO contentChunk(String connectionId, String path, Long offset, Integer limit) throws Exception {
        return toContentDTO(sshFileDomainService.content(connectionId, path, offset, limit));
    }

    public void createFile(String connectionId, String path, boolean sudo) throws Exception {
        sshFileDomainService.createFile(connectionId, path, sudo);
    }

    public void createDirectory(String connectionId, String path, boolean sudo) throws Exception {
        sshFileDomainService.createDirectory(connectionId, path, sudo);
    }

    public void rename(String connectionId, String oldPath, String newPath, boolean sudo) throws Exception {
        sshFileDomainService.rename(connectionId, oldPath, newPath, sudo);
    }

    public void delete(String connectionId, String path, boolean sudo) throws Exception {
        sshFileDomainService.delete(connectionId, path, sudo);
    }

    public void save(String connectionId, String path, String content, boolean sudo) throws Exception {
        sshFileDomainService.saveFile(connectionId, path, content, sudo);
    }

    public void upload(String connectionId, String path, InputStream inputStream) throws Exception {
        sshFileDomainService.uploadFile(connectionId, path, inputStream);
    }

    public void download(String connectionId, String path, OutputStream outputStream) throws Exception {
        sshFileDomainService.downloadFile(connectionId, path, outputStream);
    }

    private static SshFileTreeResponseDTO toTreeDTO(SshFileTreeEntity entity) {
        List<SshFileEntryDTO> items = entity.getItems() == null
                ? Collections.emptyList()
                : entity.getItems().stream().map(SshFileCase::toEntryDTO).toList();
        return SshFileTreeResponseDTO.builder()
                .rootPath(entity.getRootPath()).homePath(entity.getHomePath())
                .currentPath(entity.getCurrentPath()).parentPath(entity.getParentPath())
                .items(items).build();
    }

    private static SshFileEntryDTO toEntryDTO(SshFileEntryEntity item) {
        return SshFileEntryDTO.builder()
                .name(item.getName()).path(item.getPath()).directory(item.isDirectory())
                .size(item.getSize()).modifiedAt(item.getModifiedAt()).build();
    }

    private static SshFileContentResponseDTO toContentDTO(SshFileContentEntity entity) {
        return SshFileContentResponseDTO.builder()
                .path(entity.getPath()).name(entity.getName()).charset(entity.getCharset())
                .size(entity.getSize()).binary(entity.isBinary())
                .truncated(entity.isTruncated())
                .offset(entity.getOffset())
                .remaining(entity.getOffset() != null && entity.getSize() != null
                        ? Math.max(0, entity.getSize() - entity.getOffset() - entity.getContent().getBytes(StandardCharsets.UTF_8).length)
                        : null)
                .content(entity.getContent())
                .parsedType(entity.getParsedType()).build();
    }
}
