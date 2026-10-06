package com.shellmind.domain.coding.model.entity;

import com.shellmind.domain.coding.model.valobj.CodePatchStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.UUID;

/**
 * Code patch (aggregate root): one agent write to a local file and its audit status.
 * <p>
 * State machine:
 * <pre>
 *   APPLIED ──approve──▶ APPROVED
 *      │                    │
 *      ├──reject───────────┼──▶ REJECTED     (file restored)
 *      └──markRolledBack───┴──▶ ROLLED_BACK  (file restored)
 * </pre>
 * REJECTED / ROLLED_BACK are terminal. Status may only change through the methods below.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodePatchEntity {

    private String patchId;
    private String runId;
    private String sessionId;
    private String userId;
    private String filePath;
    private String backupPath;
    private String unifiedDiff;
    private CodePatchStatus status;
    private Integer linesAdded;
    private Integer linesRemoved;
    private Date createdAt;
    private Date updatedAt;

    /**
     * Record a write that is already on disk and count diff added/removed lines.
     */
    public static CodePatchEntity applied(String runId, String sessionId, String userId,
                                          String filePath, String backupPath, String unifiedDiff) {
        int added = 0;
        int removed = 0;
        if (unifiedDiff != null) {
            for (String line : unifiedDiff.split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) added++;
                if (line.startsWith("-") && !line.startsWith("---")) removed++;
            }
        }
        return CodePatchEntity.builder()
                .patchId(UUID.randomUUID().toString())
                .runId(runId)
                .sessionId(sessionId)
                .userId(userId)
                .filePath(filePath)
                .backupPath(backupPath)
                .unifiedDiff(unifiedDiff)
                .status(CodePatchStatus.APPLIED)
                .linesAdded(added)
                .linesRemoved(removed)
                .build();
    }

    /** The change still applies to the file (can be approved, rejected, or rolled back). */
    public boolean isRevertible() {
        return status == CodePatchStatus.APPLIED || status == CodePatchStatus.APPROVED;
    }

    /** User confirmed keeping the change. */
    public void approve() {
        if (status != CodePatchStatus.APPLIED) {
            throw invalidTransition("approve");
        }
        status = CodePatchStatus.APPROVED;
    }

    /** User rejected the change (caller must restore the file first). */
    public void reject() {
        if (!isRevertible()) {
            throw invalidTransition("reject");
        }
        status = CodePatchStatus.REJECTED;
    }

    /** The change has been rolled back (caller must restore the file first). */
    public void markRolledBack() {
        if (!isRevertible()) {
            throw invalidTransition("rollback");
        }
        status = CodePatchStatus.ROLLED_BACK;
    }

    private IllegalStateException invalidTransition(String action) {
        return new IllegalStateException("Patch " + patchId + " is currently " + status + " and cannot " + action);
    }
}
