package com.shellmind.domain.coding.adapter.repository;

import com.shellmind.domain.coding.model.entity.CodePatchEntity;
import com.shellmind.domain.coding.model.valobj.CodePatchStatus;

import java.util.List;

public interface ICodePatchRepository {
    void save(CodePatchEntity entity);
    void updateStatus(String patchId, CodePatchStatus status);
    CodePatchEntity findById(String patchId);
    List<CodePatchEntity> findByRunId(String runId);
    List<CodePatchEntity> findBySessionId(String sessionId, int limit);
    List<CodePatchEntity> findUnapprovedBySessionId(String sessionId, int limit);
}
