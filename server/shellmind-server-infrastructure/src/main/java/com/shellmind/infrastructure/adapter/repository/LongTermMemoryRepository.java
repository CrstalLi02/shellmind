package com.shellmind.infrastructure.adapter.repository;

import com.shellmind.domain.memory.adapter.repository.ILongTermMemoryRepository;
import com.shellmind.domain.memory.model.entity.LongTermMemoryEntity;
import com.shellmind.infrastructure.dao.ILongTermMemoryDao;
import com.shellmind.infrastructure.dao.po.LongTermMemoryPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Long-term memory repository (infrastructure layer).
 * <p>
 * Implements {@link ILongTermMemoryRepository} and talks to the database through a MyBatis DAO.
 * <p>
 * Core logic:
 * <ul>
 *   <li>{@link #saveOrUpdate} — lookup-then-insert/update upsert keyed by
 *       (userId, memoryType, memoryKey): INSERT when missing, UPDATE and hit_count+1 when present</li>
 *   <li>{@link #queryRecentByUserId} — latest N rows ordered by updated_at DESC (coarse filter)</li>
 * </ul>
 * <p>
 * Why not {@code INSERT ... ON DUPLICATE KEY UPDATE}?
 * hit_count must increment the existing value and several fields are updated, so lookup-then-write
 * is clearer and makes it easy to log "new memory" vs "updated memory". Writes are infrequent,
 * so performance is not the bottleneck.
 *
 * @see ILongTermMemoryRepository
 */
@Repository
public class LongTermMemoryRepository implements ILongTermMemoryRepository {

    @Resource
    private ILongTermMemoryDao longTermMemoryDao;

    /**
     * {@inheritDoc}
     * <p>
     * Upsert: look up by unique key → INSERT if missing → UPDATE + hit_count+1 if present.
     */
    @Override
    public void saveOrUpdate(LongTermMemoryEntity memoryEntity) {
        LongTermMemoryPO exist = longTermMemoryDao.queryByIdentity(
                memoryEntity.getUserId(),
                memoryEntity.getMemoryType(),
                memoryEntity.getMemoryKey()
        );

        if (exist == null) {
            // Missing → create a new memory
            longTermMemoryDao.insert(toPO(memoryEntity));
            return;
        }

        // Present → update content and increment hit_count
        exist.setSessionId(memoryEntity.getSessionId());
        exist.setContent(memoryEntity.getContent());
        exist.setKeywords(memoryEntity.getKeywords());
        exist.setSourceRole(memoryEntity.getSourceRole());
        exist.setConfidence(memoryEntity.getConfidence());
        exist.setHitCount((exist.getHitCount() == null ? 0 : exist.getHitCount()) + 1);
        longTermMemoryDao.update(exist);
    }

    /**
     * {@inheritDoc}
     * <p>
     * SQL: ORDER BY updated_at DESC, id DESC LIMIT N — latest N memories for ranking.
     */
    @Override
    public List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit) {
        List<LongTermMemoryPO> list = longTermMemoryDao.queryRecentByUserId(userId, limit);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(this::toEntity).collect(Collectors.toList());
    }

    // ==================== PO ↔ Entity conversion ====================

    private LongTermMemoryPO toPO(LongTermMemoryEntity entity) {
        return LongTermMemoryPO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .sessionId(entity.getSessionId())
                .memoryType(entity.getMemoryType())
                .memoryKey(entity.getMemoryKey())
                .content(entity.getContent())
                .keywords(entity.getKeywords())
                .sourceRole(entity.getSourceRole())
                .confidence(entity.getConfidence())
                .hitCount(entity.getHitCount())
                .build();
    }

    private LongTermMemoryEntity toEntity(LongTermMemoryPO po) {
        return LongTermMemoryEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .memoryType(po.getMemoryType())
                .memoryKey(po.getMemoryKey())
                .content(po.getContent())
                .keywords(po.getKeywords())
                .sourceRole(po.getSourceRole())
                .confidence(po.getConfidence())
                .hitCount(po.getHitCount())
                .createdAt(po.getCreatedAt() != null ? new Date(Timestamp.valueOf(po.getCreatedAt()).getTime()) : null)
                .updatedAt(po.getUpdatedAt() != null ? new Date(Timestamp.valueOf(po.getUpdatedAt()).getTime()) : null)
                .build();
    }
}
