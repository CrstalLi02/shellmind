package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * File-content response
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshFileContentResponseDTO {

    /** Absolute file path */
    private String path;

    /** File name */
    private String name;

    /** File encoding, default UTF-8 */
    private String charset;

    /** File size (bytes) */
    private Long size;

    /** Whether likely binary */
    private boolean binary;

    /** Whether truncated because it was too large */
    private boolean truncated;

    /** Start offset for chunked reads */
    private Long offset;

    /** Unread bytes remaining after a chunked read (whether more content remains) */
    private Long remaining;

    /** File content (text) */
    private String content;

    /**
     * Parsed-type id for binary files (e.g. "java-class").
     * When non-empty, content is a structured text view of the parse (read-only).
     */
    private String parsedType;

}
