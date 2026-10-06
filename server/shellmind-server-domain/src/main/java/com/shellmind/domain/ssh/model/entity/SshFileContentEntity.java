package com.shellmind.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * File-content entity
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshFileContentEntity {

    private String path;
    private String name;
    private String charset;
    private Long size;
    private boolean binary;
    private boolean truncated;
    /** Start offset for a chunked read. */
    private Long offset;
    private String content;

    /**
     * Parsed type of a binary file (e.g. "java-class").
     * Non-null means content is a structured text view of the parse result (read-only).
     */
    private String parsedType;

}
