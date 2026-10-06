package com.shellmind.infrastructure.adapter.repository;

import com.shellmind.domain.memory.adapter.repository.ICoreMemoryRepository;
import com.shellmind.domain.memory.model.valobj.CoreMemoryVO;
import com.shellmind.infrastructure.dao.ICoreMemoryDao;
import com.shellmind.infrastructure.dao.po.CoreMemoryPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core-memory repository.
 * <p>
 * Relevance matching:
 * 1. Split the query into keywords
 * 2. Find memory rows whose keywords field contains any of those terms
 * 3. If nothing matches, fall back to the most recent high-priority memories
 *
 * @author ShellMind tutorial edition
 * 2026/6/26
 */
@Slf4j
@Repository
public class CoreMemoryRepository implements ICoreMemoryRepository {

    @Resource
    private ICoreMemoryDao coreMemoryDao;

    @Override
    public void addMemory(CoreMemoryVO memory) {
        CoreMemoryPO po = toPO(memory);
        coreMemoryDao.insert(po);
        log.debug("Core memory stored: category={}, title={}", memory.getCategory(), memory.getTitle());
    }

    @Override
    public List<CoreMemoryVO> getRelevantMemories(String userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return getAllMemories(userId, limit);
        }

        // Split the query into keywords
        List<String> keywords = splitToKeywords(query);
        if (keywords.isEmpty()) {
            return getAllMemories(userId, limit);
        }

        List<CoreMemoryPO> pos = coreMemoryDao.queryByUserIdAndKeywords(userId, keywords, limit);
        if (pos == null || pos.isEmpty()) {
            // No keyword match: fall back to the most recent high-priority memories
            log.debug("No core-memory keyword match; falling back to recent memories: userId={}", userId);
            return getAllMemories(userId, limit);
        }

        return pos.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public List<CoreMemoryVO> getAllMemories(String userId, int limit) {
        List<CoreMemoryPO> pos = coreMemoryDao.queryByUserId(userId, limit);
        if (pos == null || pos.isEmpty()) return Collections.emptyList();
        return pos.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public void touchMemory(Long memoryId) {
        coreMemoryDao.updateUseStats(memoryId, new Date(), 1);  // useCount increment is applied in the DB
    }

    @Override
    public void evictColdMemories(String userId, int maxMemories) {
        int currentCount = coreMemoryDao.countByUserId(userId);
        if (currentCount <= maxMemories) return;

        // Find cold memories: unused for 30 days and priority ≤ 3
        List<CoreMemoryPO> coldMemories = coreMemoryDao.queryColdMemories(userId, 30, 3, currentCount - maxMemories);
        for (CoreMemoryPO cold : coldMemories) {
            coreMemoryDao.deleteById(cold.getId());
            log.info("Evicted cold memory: id={}, title={}, lastUsedAt={}", cold.getId(), cold.getTitle(), cold.getLastUsedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Conversion helpers
    // ═══════════════════════════════════════════════════════════════

    private CoreMemoryPO toPO(CoreMemoryVO vo) {
        return CoreMemoryPO.builder()
                .userId(vo.getUserId() == null || vo.getUserId().isBlank() ? "default" : vo.getUserId())
                .scope(vo.getScope())
                .category(vo.getCategory())
                .title(vo.getTitle())
                .keywords(vo.getKeywords())
                .content(vo.getContent())
                .priority(vo.getPriority())
                .sourceSessionId(vo.getSourceSessionId())
                .useCount(vo.getUseCount() != null ? vo.getUseCount() : 1)
                .createdAt(new Date(vo.getCreatedAt() != null ? vo.getCreatedAt() : System.currentTimeMillis()))
                .lastUsedAt(new Date(vo.getLastUsedAt() != null ? vo.getLastUsedAt() : System.currentTimeMillis()))
                .build();
    }

    private CoreMemoryVO toVO(CoreMemoryPO po) {
        return CoreMemoryVO.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .scope(po.getScope())
                .category(po.getCategory())
                .title(po.getTitle())
                .keywords(po.getKeywords())
                .content(po.getContent())
                .priority(po.getPriority())
                .createdAt(po.getCreatedAt() != null ? po.getCreatedAt().getTime() : null)
                .lastUsedAt(po.getLastUsedAt() != null ? po.getLastUsedAt().getTime() : null)
                .useCount(po.getUseCount())
                .sourceSessionId(po.getSourceSessionId())
                .build();
    }

    private List<String> splitToKeywords(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        // Mixed tokenization: split on whitespace, commas, and CJK punctuation
        String[] tokens = query.split("[\\s,，。.!！?？;；:：/\\\\|()\\[\\]{}\"']+");
        List<String> keywords = new ArrayList<>();

        for (String token : tokens) {
            if (token.length() < 2) continue;
            keywords.add(token.toLowerCase());
            // Split English camelCase (e.g. "UserService" → "user", "service")
            if (token.matches("[A-Z][a-z]+[A-Z][a-z]+")) {
                String[] parts = token.split("(?=[A-Z])");
                for (String part : parts) {
                    if (part.length() >= 2) keywords.add(part.toLowerCase());
                }
            }
        }

        return keywords;
    }
}
