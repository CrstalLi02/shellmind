package com.shellmind.infrastructure.adapter.repository;

import com.shellmind.domain.llm.adapter.repository.IModelConfigRepository;
import com.shellmind.domain.llm.model.entity.ModelConfigEntity;
import com.shellmind.infrastructure.dao.IModelConfigDao;
import com.shellmind.infrastructure.dao.po.ModelConfigPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class ModelConfigRepository implements IModelConfigRepository {

    @Resource
    private IModelConfigDao modelConfigDao;

    @Override
    public List<ModelConfigEntity> queryAll() {
        return modelConfigDao.queryAll().stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public ModelConfigEntity queryById(Long id) {
        ModelConfigPO po = modelConfigDao.queryById(id);
        return po == null ? null : toEntity(po);
    }

    @Override
    public ModelConfigEntity insert(ModelConfigEntity entity) {
        ModelConfigPO po = toPO(entity);
        modelConfigDao.insert(po);
        return queryById(po.getId());
    }

    @Override
    public void update(ModelConfigEntity entity) {
        modelConfigDao.update(toPO(entity));
    }

    @Override
    public void deleteById(Long id) {
        modelConfigDao.deleteById(id);
    }

    private ModelConfigEntity toEntity(ModelConfigPO po) {
        return ModelConfigEntity.builder()
                .id(po.getId())
                .name(po.getName())
                .baseUrl(po.getBaseUrl())
                .apiKey(po.getApiKey())
                .modelName(po.getModelName())
                .completionsPath(po.getCompletionsPath())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private ModelConfigPO toPO(ModelConfigEntity entity) {
        return ModelConfigPO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .baseUrl(entity.getBaseUrl())
                .apiKey(entity.getApiKey())
                .modelName(entity.getModelName())
                .completionsPath(entity.getCompletionsPath())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
