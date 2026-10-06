package com.shellmind.domain.memory.adapter.repository;

import com.shellmind.domain.memory.model.entity.LongTermMemoryEntity;

import java.util.List;

/**
 * Long-term memory persistence repository (domain-layer abstraction).
 * <p>
 * Defines upsert writes and per-user queries for long-term memory, implemented by
 * {@code LongTermMemoryRepository} in infrastructure.
 * <p>
 * Writes use a lookup-then-insert/update upsert: the unique triple (userId, memoryType, memoryKey)
 * decides whether a row already exists. Missing rows are INSERT-ed; existing rows are UPDATE-ed
 * with hit_count+1. The same preference/fact is not stored twice; hit_count records how often it was mentioned.
 *
 * @see com.shellmind.infrastructure.adapter.repository.LongTermMemoryRepository
 */
public interface ILongTermMemoryRepository {

    /**
     * Save or update a long-term memory (upsert).
     * <p>
     * Lookup by unique key (userId, memoryType, memoryKey):
     * <ul>
     *   <li>Missing → INSERT a new memory</li>
     *   <li>Exists → UPDATE content/keywords/confidence and increment hit_count</li>
     * </ul>
     *
     * @param memoryEntity long-term memory entity
     */
    void saveOrUpdate(LongTermMemoryEntity memoryEntity);

    /**
     * Query the N most recent long-term memories for a user (coarse-filter stage).
     * <p>
     * SQL orders by updated_at DESC, id DESC and takes the latest N rows.
     * Actual "search" is keyword-overlap scoring in LongTermMemoryService (fine-ranking stage).
     *
     * @param userId user ID
     * @param limit  max rows (LongTermMemoryService passes 120)
     * @return long-term memory list
     */
    List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit);
}
