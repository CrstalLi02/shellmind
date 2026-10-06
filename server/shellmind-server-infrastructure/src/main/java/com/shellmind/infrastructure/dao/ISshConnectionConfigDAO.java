package com.shellmind.infrastructure.dao;

import com.shellmind.infrastructure.dao.po.SshConnectionConfigPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * SSH connection advanced-config DAO.
 *
 * @author waissh dev
 */
@Mapper
public interface ISshConnectionConfigDAO {

    void insertOrUpdate(SshConnectionConfigPO po);

    SshConnectionConfigPO queryByConnectionId(String connectionId);

}
