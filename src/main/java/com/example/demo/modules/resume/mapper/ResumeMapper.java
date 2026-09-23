package com.example.demo.modules.resume.mapper;

import com.example.demo.modules.resume.model.entity.ResumeAnalysisEntity;
import com.example.demo.modules.resume.model.entity.ResumeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface ResumeMapper {

    // ===== 简历基础表 =====

    int insertResume(ResumeEntity resume);

    int updateResume(ResumeEntity resume);

    int updateResumeAnalysisResult(
            @Param("id") Long id,
            @Param("analyzeStatus") String analyzeStatus,
            @Param("analyzeError") String analyzeError,
            @Param("latestScore") BigDecimal latestScore
    );

    int incrementAccessCount(@Param("id") Long id);

    int incrementInterviewCount(@Param("id") Long id);

    ResumeEntity findResumeById(@Param("id") Long id);

    ResumeEntity findResumeByHash(@Param("userId") Long userId, @Param("fileHash") String fileHash);

    List<ResumeEntity> findResumesByUserId(@Param("userId") Long userId);

    int deleteResumeById(@Param("id") Long id, @Param("userId") Long userId);

    // ===== 简历分析报告表 =====

    int insertAnalysis(ResumeAnalysisEntity analysis);

    List<ResumeAnalysisEntity> findAnalysesByResumeId(@Param("resumeId") Long resumeId);

    ResumeAnalysisEntity findLatestAnalysisByResumeId(@Param("resumeId") Long resumeId);

    int deleteAnalysesByResumeId(@Param("resumeId") Long resumeId);
}
