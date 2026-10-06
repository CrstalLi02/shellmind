package com.shellmind.domain.coding.service;

import com.shellmind.domain.coding.adapter.repository.ICodePatchRepository;
import com.shellmind.domain.coding.model.entity.CodePatchEntity;
import com.shellmind.domain.coding.model.valobj.CodePatchStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

@Slf4j
@Service
public class CodePatchAuditService {

    @Resource
    private ICodePatchRepository codePatchRepository;

    @Resource
    private CodePatchService codePatchService;

    public CodePatchAuditService() {}

    public CodePatchAuditService(ICodePatchRepository codePatchRepository, CodePatchService codePatchService) {
        this.codePatchRepository = codePatchRepository;
        this.codePatchService = codePatchService;
    }

    public CodePatchEntity recordPatch(
            String runId, String sessionId, String userId,
            String filePath, String backupPath, String unifiedDiff) {
        CodePatchEntity entity = CodePatchEntity.applied(runId, sessionId, userId, filePath, backupPath, unifiedDiff);
        codePatchRepository.save(entity);
        log.info("Patch audit record saved: patchId={}, file={}, +{} -{}",
                entity.getPatchId(), filePath, entity.getLinesAdded(), entity.getLinesRemoved());
        return entity;
    }

    /**
     * Approve a patch (only APPLIED status can be approved)
     *
     * @return whether approval succeeded
     */
    public boolean approve(String patchId) {
        CodePatchEntity patch = codePatchRepository.findById(patchId);
        if (patch == null || patch.getStatus() != CodePatchStatus.APPLIED) {
            log.warn("Patch cannot be approved: patchId={}, status={}", patchId, patch == null ? null : patch.getStatus());
            return false;
        }
        patch.approve();
        codePatchRepository.updateStatus(patchId, patch.getStatus());
        return true;
    }

    /**
     * Reject a patch: restore the file and mark REJECTED
     *
     * @return whether rejection succeeded (file restored)
     */
    public boolean reject(String patchId) {
        return revert(patchId, CodePatchEntity::reject, "reject");
    }

    /**
     * Roll back a patch: restore the file and mark ROLLED_BACK
     *
     * @return whether rollback succeeded
     */
    public boolean rollbackPatch(String patchId) {
        return revert(patchId, CodePatchEntity::markRolledBack, "rollback");
    }

    private boolean revert(String patchId, java.util.function.Consumer<CodePatchEntity> transition, String action) {
        CodePatchEntity patch = codePatchRepository.findById(patchId);
        if (patch == null || !patch.isRevertible()) {
            return false;
        }
        try {
            java.nio.file.Path target = java.nio.file.Path.of(patch.getFilePath());
            if (!codePatchService.rollback(target, patch.getBackupPath())) {
                return false;
            }
            transition.accept(patch);
            codePatchRepository.updateStatus(patchId, patch.getStatus());
            log.info("Patch {}: patchId={}", action, patchId);
            return true;
        } catch (Exception e) {
            log.error("Patch {} failed: patchId={}", action, patchId, e);
            return false;
        }
    }

    public CodePatchEntity findById(String patchId) {
        return codePatchRepository.findById(patchId);
    }

    public List<CodePatchEntity> findByRunId(String runId) {
        return codePatchRepository.findByRunId(runId);
    }

    public List<CodePatchEntity> findBySessionId(String sessionId, int limit) {
        return codePatchRepository.findBySessionId(sessionId, limit);
    }

    public List<CodePatchEntity> findUnapprovedBySessionId(String sessionId, int limit) {
        return codePatchRepository.findUnapprovedBySessionId(sessionId, limit);
    }

    /**
     * Roll back all applied patches in a run (newest first so dependents restore correctly)
     *
     * @return number of patches rolled back
     */
    public int rollbackRun(String runId) {
        List<CodePatchEntity> patches = codePatchRepository.findByRunId(runId);
        int rolledBack = 0;
        for (CodePatchEntity patch : patches) {
            if (patch.isRevertible()) {
                if (rollbackPatch(patch.getPatchId())) {
                    rolledBack++;
                }
            }
        }
        log.info("Run batch rollback finished: runId={}, total={}, rolledBack={}", runId, patches.size(), rolledBack);
        return rolledBack;
    }

}
