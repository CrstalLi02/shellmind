package com.shellmind.infrastructure.adapter.repository;

import com.shellmind.domain.coding.adapter.repository.ICodePatchRepository;
import com.shellmind.domain.coding.model.entity.CodePatchEntity;
import com.shellmind.domain.coding.model.valobj.CodePatchStatus;
import com.shellmind.infrastructure.dao.ICodePatchDao;
import com.shellmind.infrastructure.dao.po.CodePatchPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class CodePatchRepository implements ICodePatchRepository {

    @Resource
    private ICodePatchDao codePatchDao;

    @Override
    public void save(CodePatchEntity entity) {
        codePatchDao.insert(toPO(entity));
    }

    @Override
    public void updateStatus(String patchId, CodePatchStatus status) {
        CodePatchPO po = new CodePatchPO();
        po.setPatchId(patchId);
        po.setStatus(status.name());
        codePatchDao.updateStatus(po);
    }

    @Override
    public CodePatchEntity findById(String patchId) {
        CodePatchPO po = codePatchDao.queryById(patchId);
        return po == null ? null : toEntity(po);
    }

    @Override
    public List<CodePatchEntity> findByRunId(String runId) {
        return codePatchDao.queryByRunId(runId).stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public List<CodePatchEntity> findBySessionId(String sessionId, int limit) {
        return codePatchDao.queryBySessionId(sessionId, limit).stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public List<CodePatchEntity> findUnapprovedBySessionId(String sessionId, int limit) {
        return codePatchDao.queryUnapprovedBySessionId(sessionId, limit).stream().map(this::toEntity).collect(Collectors.toList());
    }

    private CodePatchPO toPO(CodePatchEntity entity) {
        return CodePatchPO.builder()
                .patchId(entity.getPatchId())
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .filePath(entity.getFilePath())
                .backupPath(entity.getBackupPath())
                .unifiedDiff(entity.getUnifiedDiff())
                .status(entity.getStatus() == null ? null : entity.getStatus().name())
                .linesAdded(entity.getLinesAdded())
                .linesRemoved(entity.getLinesRemoved())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private CodePatchEntity toEntity(CodePatchPO po) {
        return CodePatchEntity.builder()
                .patchId(po.getPatchId())
                .runId(po.getRunId())
                .sessionId(po.getSessionId())
                .userId(po.getUserId())
                .filePath(po.getFilePath())
                .backupPath(po.getBackupPath())
                .unifiedDiff(po.getUnifiedDiff())
                .status(po.getStatus() == null ? null : CodePatchStatus.valueOf(po.getStatus()))
                .linesAdded(po.getLinesAdded())
                .linesRemoved(po.getLinesRemoved())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
