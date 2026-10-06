package com.shellmind.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * File-tree entity (single directory level)
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshFileTreeEntity {

    private String rootPath;
    private String homePath;
    private String currentPath;
    private String parentPath;
    private List<SshFileEntryEntity> items;

}
