package com.shellmind.cases.coding;

import com.shellmind.api.dto.CodePatchResponseDTO;
import com.shellmind.domain.coding.model.entity.CodePatchEntity;
import com.shellmind.domain.coding.service.CodePatchAuditService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Code-change audit use case: query, approve, reject, and rollback.
 */
@Service
public class CodePatchCase {

    @Resource
    private CodePatchAuditService codePatchAuditService;

    public Optional<CodePatchResponseDTO> queryPatch(String patchId) {
        return Optional.ofNullable(codePatchAuditService.findById(patchId)).map(this::toResponse);
    }

    public List<CodePatchResponseDTO> queryByRun(String runId) {
        return codePatchAuditService.findByRunId(runId).stream().map(this::toResponse).toList();
    }

    public List<CodePatchResponseDTO> queryBySession(String sessionId, int limit) {
        return codePatchAuditService.findBySessionId(sessionId, limit).stream().map(this::toResponse).toList();
    }

    public List<CodePatchResponseDTO> queryUnapproved(String sessionId, int limit) {
        return codePatchAuditService.findUnapprovedBySessionId(sessionId, limit).stream().map(this::toResponse).toList();
    }

    public boolean rollback(String patchId) {
        return codePatchAuditService.rollbackPatch(patchId);
    }

    public int rollbackRun(String runId) {
        return codePatchAuditService.rollbackRun(runId);
    }

    public boolean approve(String patchId) {
        return codePatchAuditService.approve(patchId);
    }

    public boolean reject(String patchId) {
        return codePatchAuditService.reject(patchId);
    }

    private CodePatchResponseDTO toResponse(CodePatchEntity entity) {
        CodePatchResponseDTO r = new CodePatchResponseDTO();
        r.setPatchId(entity.getPatchId());
        r.setRunId(entity.getRunId());
        r.setSessionId(entity.getSessionId());
        r.setFilePath(entity.getFilePath());
        r.setStatus(entity.getStatus() != null ? entity.getStatus().name() : null);
        r.setLinesAdded(entity.getLinesAdded());
        r.setLinesRemoved(entity.getLinesRemoved());
        r.setCreatedAt(entity.getCreatedAt());
        return r;
    }
}
