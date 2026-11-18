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

    /** 按主键查询（不带归属过滤，仅供内部任务和归属校验使用） */
    KnowledgeBase findById(@Param("id") Long id);

    /** 按主键列表查询（不带归属过滤，调用前必须已校验归属） */
    List<KnowledgeBase> findByIds(@Param("ids") List<Long> ids);

    /** 某个用户的全部知识库 */
    List<KnowledgeBase> findAllByUser(@Param("userId") Long userId);

    /** 某个用户的全部知识库 id（用于限定检索范围） */
    List<Long> findIdsByUser(@Param("userId") Long userId);

    /** 根据文件哈希查询（同一用户内去重） */
    KnowledgeBase findByFileHash(@Param("userId") Long userId, @Param("fileHash") String fileHash);

    /** 按名称搜索某个用户的知识库 */
    List<KnowledgeBase> searchByName(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 某个用户的全部分类 */
    List<String> findAllCategories(@Param("userId") Long userId);

    /** 某个用户的知识库总数 */
    int countAll(@Param("userId") Long userId);

    /** 某个用户按向量化状态统计 */
    int countByVectorStatus(@Param("userId") Long userId, @Param("status") String status);

    /** 更新向量化状态 */
    void updateVectorStatus(@Param("id") Long id,
                           @Param("vectorStatus") String vectorStatus,
                           @Param("vectorError") String vectorError,
                           @Param("chunkCount") Integer chunkCount);

    /** 删除知识库 */
    void deleteById(@Param("id") Long id);
}
