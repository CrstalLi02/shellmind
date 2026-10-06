package com.shellmind.domain.memory.adapter.repository;

import com.shellmind.domain.memory.model.valobj.CoreMemoryVO;

import java.util.List;

/**
 * Core memory repository.
 * <p>
 * Stores long-term user preferences, rules, corrections, and key decisions.
 * Unlike IChatHistoryRepository (session-level messages), this interface manages cross-session memory.
 *
 * @author ShellMind
 * 2026/6/26
 */
public interface ICoreMemoryRepository {

    /**
     * Add a core memory.
     */
    void addMemory(CoreMemoryVO memory);

    /**
     * Query related memories by keywords (relevance filter).
     * <p>
     * Matching: intersection of keywords in the keywords field with keywords in the query.
     * At least one keyword hit is required.
     *
     * @param userId user ID (for scope=user global memories)
     * @param query  query text (extracted from the user message)
     * @param limit  max results
     * @return related memories
     */
    List<CoreMemoryVO> getRelevantMemories(String userId, String query, int limit);

    /**
     * Get all memories (no relevance filter; used as fallback).
     */
    List<CoreMemoryVO> getAllMemories(String userId, int limit);

    /**
     * Update memory usage (lastUsedAt and useCount after a hit).
     */
    void touchMemory(Long memoryId);

    /**
     * Evict cold memories (delete low-priority memories that have not been used for a long time).
     */
    void evictColdMemories(String userId, int maxMemories);
}
