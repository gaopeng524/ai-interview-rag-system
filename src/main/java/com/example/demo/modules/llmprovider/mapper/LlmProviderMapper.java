package com.example.demo.modules.llmprovider.mapper;

import com.example.demo.modules.llmprovider.model.entity.LlmProviderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 大模型供应商持久化 Mapper
 */
@Mapper
public interface LlmProviderMapper {

    /** 查询所有供应商配置 */
    List<LlmProviderEntity> findAll();

    /** 根据 ID 查询 */
    LlmProviderEntity findById(@Param("id") String id);

    /** 插入新配置 */
    void insert(LlmProviderEntity entity);

    /** 更新配置 */
    void update(LlmProviderEntity entity);

    /** 删除配置 */
    void deleteById(@Param("id") String id);

    /** 重置所有默认状态为 false */
    void resetAllDefault();

    /** 设置指定 providerId 为文字默认 */
    void setDefault(@Param("id") String id);

    /** 重置所有向量默认状态为 false */
    void resetAllDefaultEmbedding();

    /** 设置指定 providerId 为向量默认 */
    void setDefaultEmbedding(@Param("id") String id);
}
