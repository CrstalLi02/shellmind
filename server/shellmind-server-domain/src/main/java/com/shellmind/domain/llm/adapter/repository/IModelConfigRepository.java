package com.shellmind.domain.llm.adapter.repository;

import com.shellmind.domain.llm.model.entity.ModelConfigEntity;

import java.util.List;

public interface IModelConfigRepository {

    List<ModelConfigEntity> queryAll();

    ModelConfigEntity queryById(Long id);

    ModelConfigEntity insert(ModelConfigEntity entity);

    void update(ModelConfigEntity entity);

    void deleteById(Long id);
}
