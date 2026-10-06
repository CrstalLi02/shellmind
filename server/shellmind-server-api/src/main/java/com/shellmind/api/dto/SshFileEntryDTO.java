package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * File-node DTO
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshFileEntryDTO {

    /** Node name (without path) */
    private String name;

    /** Absolute path */
    private String path;

    /** Whether a directory */
    private boolean directory;

    /** File size (may be null for directories) */
    private Long size;

    /** Modified time (epoch millis) */
    private Long modifiedAt;

}
