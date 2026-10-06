package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * File-tree query response
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshFileTreeResponseDTO {

    /** Root path, always "/" */
    private String rootPath;

    /** User home directory */
    private String homePath;

    /** Current directory */
    private String currentPath;

    /** Parent directory; empty at root */
    private String parentPath;

    /** Children of the current directory */
    private List<SshFileEntryDTO> items;

}
