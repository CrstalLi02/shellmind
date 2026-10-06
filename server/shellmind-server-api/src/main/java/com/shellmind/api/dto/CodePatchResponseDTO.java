package com.shellmind.api.dto;

import lombok.Data;

import java.util.Date;

/**
 * Code-change audit record
 */
@Data
public class CodePatchResponseDTO {
    private String patchId;
    private String runId;
    private String sessionId;
    private String filePath;
    private String status;
    private Integer linesAdded;
    private Integer linesRemoved;
    private Date createdAt;
}
