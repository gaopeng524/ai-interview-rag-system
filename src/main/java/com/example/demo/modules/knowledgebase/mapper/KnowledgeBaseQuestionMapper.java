package com.example.demo.modules.knowledgebase.mapper;

import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseQuestionDTO;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBaseQuestionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeBaseQuestionMapper {

    int batchInsert(@Param("list") List<KnowledgeBaseQuestionEntity> list);

    int insert(KnowledgeBaseQuestionEntity entity);

    int update(KnowledgeBaseQuestionEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int deleteById(@Param("id") Long id);

    int deleteByKnowledgeBaseId(@Param("kbId") Long kbId);

    KnowledgeBaseQuestionEntity findById(@Param("id") Long id);

    List<KnowledgeBaseQuestionEntity> findQuestions(
            @Param("kbId") Long kbId,
            @Param("status") String status,
            @Param("category") String category,
            @Param("difficulty") String difficulty,
            @Param("keyword") String keyword
    );

    List<KnowledgeBaseQuestionDTO.CategoryCount> countCategoriesByKbId(@Param("kbId") Long kbId);

    int countByKbId(@Param("kbId") Long kbId);
}
