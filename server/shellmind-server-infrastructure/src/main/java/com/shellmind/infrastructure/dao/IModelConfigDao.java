package com.shellmind.infrastructure.dao;

import com.shellmind.infrastructure.dao.po.ModelConfigPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IModelConfigDao {

    List<ModelConfigPO> queryAll();

    ModelConfigPO queryById(Long id);

    void insert(ModelConfigPO po);

    void update(ModelConfigPO po);

    void deleteById(Long id);
}
