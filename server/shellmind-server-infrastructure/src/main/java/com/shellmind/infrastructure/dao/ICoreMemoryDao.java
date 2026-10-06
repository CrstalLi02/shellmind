package com.shellmind.infrastructure.dao;

import com.shellmind.infrastructure.dao.po.CoreMemoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ICoreMemoryDao {
    void insert(CoreMemoryPO po);

    List<CoreMemoryPO> queryByUserIdAndKeywords(@Param("userId") String userId,
                                                  @Param("keywords") List<String> keywords,
                                                  @Param("limit") int limit);

    List<CoreMemoryPO> queryByUserId(@Param("userId") String userId,
                                       @Param("limit") int limit);

    void updateUseStats(@Param("id") Long id,
                        @Param("lastUsedAt") java.util.Date lastUsedAt,
                        @Param("useCount") int useCount);

    void deleteById(@Param("id") Long id);

    List<CoreMemoryPO> queryColdMemories(@Param("userId") String userId,
                                          @Param("minAgeDays") int minAgeDays,
                                          @Param("maxPriority") int maxPriority,
                                          @Param("limit") int limit);

    int countByUserId(@Param("userId") String userId);
}
