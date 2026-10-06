package com.shellmind.infrastructure.dao;

import com.shellmind.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IChatSessionDao {
    void insert(ChatSessionPO po);
    void updateMessageCount(String id);
    ChatSessionPO queryById(String id);
}
