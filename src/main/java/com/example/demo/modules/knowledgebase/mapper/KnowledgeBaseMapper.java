package com.example.demo.modules.knowledgebase.mapper;

import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库 Mapper
 */
@Mapper
public interface KnowledgeBaseMapper {

    /** 创建知识库表 */
    void createTable();

    /** 插入知识库 */
    void insert(KnowledgeBase knowledgeBase);

    /** 根据ID查询 */
    KnowledgeBase findById(@Param("id") Long id);

    /** 根据ID列表查询 */
    List<KnowledgeBase> findByIds(@Param("ids") List<Long> ids);

    /** 查询所有知识库 */
    List<KnowledgeBase> findAll();

    /** 根据文件哈希查询（用于去重） */
    KnowledgeBase findByFileHash(@Param("fileHash") String fileHash);

    /** 按名称搜索知识库 */
    List<KnowledgeBase> searchByName(@Param("keyword") String keyword);

    /** 获取所有分类 */
    List<String> findAllCategories();

    /** 获取统计信息：总数 */
    int countAll();

    /** 获取统计信息：按状态统计 */
    int countByVectorStatus(@Param("status") String status);

    /** 更新向量化状态 */
    void updateVectorStatus(@Param("id") Long id,
                           @Param("vectorStatus") String vectorStatus,
                           @Param("vectorError") String vectorError,
                           @Param("chunkCount") Integer chunkCount);

    /** 删除知识库 */
    void deleteById(@Param("id") Long id);
}
