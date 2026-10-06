package com.shellmind.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodePatchPO {
    private String patchId;
    private String runId;
    private String sessionId;
    private String userId;
    private String filePath;
    private String backupPath;
    private String unifiedDiff;
    private String status;
    private Integer linesAdded;
    private Integer linesRemoved;
    private Date createdAt;
    private Date updatedAt;
}
