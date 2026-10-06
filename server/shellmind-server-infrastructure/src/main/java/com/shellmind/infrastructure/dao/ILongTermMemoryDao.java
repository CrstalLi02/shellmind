package com.shellmind.infrastructure.dao;

import com.shellmind.infrastructure.dao.po.LongTermMemoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Long-term memory MyBatis DAO.
 * <p>
 * Maps to the long_term_memory table and supports lookup-by-unique-key, insert, and update for upsert,
 * plus recent-memory queries by user (coarse filter).
 */
@Mapper
public interface ILongTermMemoryDao {

    /**
     * Look up by unique key (userId, memoryType, memoryKey) before upsert.
     *
     * @param userId     user ID
     * @param memoryType memory type
     * @param memoryKey  dedup key
     * @return existing memory PO, or null if none
     */
    LongTermMemoryPO queryByIdentity(@Param("userId") String userId,
                                     @Param("memoryType") String memoryType,
                                     @Param("memoryKey") String memoryKey);

    /**
     * Insert a new long-term memory.
     *
     * @param po long-term memory persistence object
     */
    void insert(LongTermMemoryPO po);

    /**
     * Update an existing long-term memory (content/keywords/confidence/hit_count, etc.).
     *
     * @param po persistence object that already exists and has updated fields
     */
    void update(LongTermMemoryPO po);

    /**
     * Query recent long-term memories for a user (coarse filter; ranking is done in the service with keyword matching).
     *
     * @param userId user ID
     * @param limit  max rows (LongTermMemoryService passes 120)
     * @return memories ordered by updated_at DESC, id DESC
     */
    List<LongTermMemoryPO> queryRecentByUserId(@Param("userId") String userId,
                                               @Param("limit") int limit);
}
