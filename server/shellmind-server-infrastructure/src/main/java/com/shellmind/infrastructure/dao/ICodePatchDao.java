package com.shellmind.infrastructure.dao;

import com.shellmind.infrastructure.dao.po.CodePatchPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ICodePatchDao {
    void insert(CodePatchPO po);
    void updateStatus(CodePatchPO po);
    CodePatchPO queryById(String patchId);
    List<CodePatchPO> queryByRunId(String runId);
    List<CodePatchPO> queryBySessionId(String sessionId, int limit);
    List<CodePatchPO> queryUnapprovedBySessionId(String sessionId, int limit);
}
