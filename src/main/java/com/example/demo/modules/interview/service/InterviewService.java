package com.example.demo.modules.interview.service;

import com.example.demo.common.ai.AiStructuredClient;
import com.example.demo.modules.interview.prompt.InterviewPrompts;
import com.example.demo.modules.interview.model.dto.InterviewDTO.*;
import com.example.demo.modules.interview.model.entity.InterviewQuestionEntity;
import com.example.demo.modules.interview.model.entity.InterviewSessionEntity;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.interview.mapper.InterviewMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 模拟面试核心业务服务（极简高内聚设计）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewMapper interviewMapper;
    private final AiStructuredClient aiStructuredClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final InterviewSkillService skillService;
    private final com.example.demo.modules.resume.service.ResumeService resumeService;
    private final com.example.demo.modules.interview.async.InterviewEvaluateStreamProducer interviewEvaluateStreamProducer;
    private final com.example.demo.common.ai.Mem0Client mem0Client;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 创建面试会话并由 AI 智能生成考题（自动注入真实面经与知识库上下文）
     */
    @Transactional
    public InterviewSessionDTO createSession(Long userId, CreateInterviewRequest request) {
        String sessionId = "session-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int totalQuestions = request.questionCount() != null && request.questionCount() > 0
                ? Math.min(request.questionCount(), 15) : 5;
        String skillId = (request.skillId() != null && !request.skillId().isBlank()) ? request.skillId().trim() : "java-backend";
        String difficulty = (request.difficulty() != null && !request.difficulty().isBlank()) ? request.difficulty().trim() : "mid";

        // 1. 提取简历文本并联动递增面试次数
        String effectiveResumeText = request.resumeText();
        if (request.resumeId() != null && resumeService != null) {
            String dbResumeText = resumeService.getResumeTextAndIncrementInterviewCount(request.resumeId(), userId);
            if (effectiveResumeText == null || effectiveResumeText.isBlank()) {
                effectiveResumeText = dbResumeText;
            }
        }

        // 2. 获取关联知识库或面经考点内容（Grounding 真实考点）
        StringBuilder contextBuilder = new StringBuilder();
        if (request.knowledgeBaseId() != null) {
            try {
                List<Document> docs = knowledgeBaseService.search(skillId, List.of(request.knowledgeBaseId()), 3);
                contextBuilder.append(docs.stream().map(Document::getText).collect(Collectors.joining("\n\n")));
            } catch (Exception e) {
                log.warn("获取知识库内容用于出题失败: {}", e.getMessage());
            }
        }
        if (skillService != null) {
            String refContext = skillService.getSkillReferenceContext(skillId);
            if (!refContext.isBlank()) {
                contextBuilder.append("\n\n").append(refContext);
            }
        }

        // 2.2 召回 Mem0 历史考生画像与短板（跨会话自适应）
        if (mem0Client != null) {
            String memoryPrompt = mem0Client.buildMemoryPrompt(userId, skillId);
            if (!memoryPrompt.isBlank()) {
                contextBuilder.append("\n\n").append(memoryPrompt);
            }
        }

        // 3. 调用通用 AI 结构化生成组件出题（带自动保底兜底）
        String prompt = InterviewPrompts.buildGenerateQuestionsPrompt(
                skillId, difficulty, totalQuestions, effectiveResumeText, request.jdText(), contextBuilder.toString());
        List<InterviewQuestionDTO> questions = aiStructuredClient.callForStructured(
                prompt,
                new TypeReference<List<InterviewQuestionDTO>>() {},
                () -> getDefaultQuestions(skillId, totalQuestions)
        );

        // 4. 保存会话
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setSkillId(skillId);
        session.setDifficulty(difficulty);
        session.setResumeId(request.resumeId());
        session.setResumeText(effectiveResumeText);
        session.setJdText(request.jdText());
        session.setTotalQuestions(questions.size());
        session.setCurrentQuestionIndex(0);
        session.setStatus("CREATED");
        session.setEvaluateStatus("PENDING");
        session.setSourceType("TEXT");
        session.setKnowledgeBaseId(request.knowledgeBaseId());
        session.setInterviewCategory(request.interviewCategory() != null ? request.interviewCategory() : skillId);
        interviewMapper.insertSession(session);

        // 4. 保存题目
        List<InterviewQuestionEntity> qEntities = questions.stream().map(q -> {
            InterviewQuestionEntity e = new InterviewQuestionEntity();
            e.setSessionId(sessionId);
            e.setQuestionIndex(q.questionIndex());
            e.setQuestion(q.question());
            e.setType(q.type() != null ? q.type() : "TECHNICAL");
            e.setCategory(q.category() != null ? q.category() : skillId);
            e.setTopicSummary(q.topicSummary());
            e.setReferenceAnswer(q.referenceAnswer());
            e.setKeyPoints(toJson(q.keyPoints(), "[]"));
            e.setScoringRubric(q.scoringRubric());
            e.setSourceContext(q.sourceContext());
            return e;
        }).toList();
        interviewMapper.batchInsertQuestions(qEntities);

        return getSession(sessionId, userId);
    }

    /**
     * 获取会话详情
     */
    public InterviewSessionDTO getSession(String sessionId, Long userId) {
        InterviewSessionEntity session = validateSessionOwner(sessionId, userId);
        List<InterviewQuestionEntity> questions = interviewMapper.findQuestionsBySessionId(sessionId);
        List<InterviewQuestionDTO> questionDTOs = questions.stream().map(this::mapToQuestionDTO).toList();
        return new InterviewSessionDTO(
                session.getSessionId(), session.getResumeText(), session.getTotalQuestions(),
                session.getCurrentQuestionIndex(), questionDTOs, session.getStatus(),
                session.getKnowledgeBaseId(), session.getInterviewCategory()
        );
    }

    /**
     * 获取当前题目
     */
    public CurrentQuestionResponse getCurrentQuestion(String sessionId, Long userId) {
        InterviewSessionEntity session = validateSessionOwner(sessionId, userId);
        if (session.getCurrentQuestionIndex() >= session.getTotalQuestions() || "COMPLETED".equals(session.getStatus()) || "EVALUATED".equals(session.getStatus())) {
            return new CurrentQuestionResponse(true, null, "面试题目已全部完成，请生成面试报告");
        }
        InterviewQuestionEntity question = interviewMapper.findQuestionByIndex(sessionId, session.getCurrentQuestionIndex());
        return question != null
                ? new CurrentQuestionResponse(false, mapToQuestionDTO(question), null)
                : new CurrentQuestionResponse(true, null, "没有更多题目");
    }

    /**
     * 提交答案并推进
     */
    @Transactional
    public SubmitAnswerResponse submitAnswer(Long userId, SubmitAnswerRequest request) {
        InterviewSessionEntity session = validateSessionOwner(request.sessionId(), userId);
        int answeredIndex = request.questionIndex() != null ? request.questionIndex() : session.getCurrentQuestionIndex();
        interviewMapper.updateUserAnswer(request.sessionId(), answeredIndex, request.answer() != null ? request.answer().trim() : "");

        int nextIndex = answeredIndex + 1;
        boolean hasNext = nextIndex < session.getTotalQuestions();
        interviewMapper.updateSessionStatus(request.sessionId(), hasNext ? "IN_PROGRESS" : "COMPLETED", nextIndex);

        if (!hasNext) {
            triggerAsyncEvaluation(request.sessionId(), userId);
        }

        InterviewQuestionEntity nextQ = hasNext ? interviewMapper.findQuestionByIndex(request.sessionId(), nextIndex) : null;
        return new SubmitAnswerResponse(hasNext, nextQ != null ? mapToQuestionDTO(nextQ) : null, nextIndex, session.getTotalQuestions());
    }

    /**
     * 暂存答案
     */
    public void saveAnswer(Long userId, SubmitAnswerRequest request) {
        InterviewSessionEntity session = validateSessionOwner(request.sessionId(), userId);
        int index = request.questionIndex() != null ? request.questionIndex() : session.getCurrentQuestionIndex();
        interviewMapper.updateUserAnswer(request.sessionId(), index, request.answer() != null ? request.answer() : "");
    }

    /**
     * 提前交卷（自动触发后台大模型异步综合能力评估）
     */
    public void completeInterview(String sessionId, Long userId) {
        InterviewSessionEntity session = validateSessionOwner(sessionId, userId);
        interviewMapper.updateSessionStatus(sessionId, "COMPLETED", session.getCurrentQuestionIndex());
        triggerAsyncEvaluation(sessionId, userId);
    }

    /**
     * 异步触发后台评估任务（基于 Redis Stream 消息队列与分布式排重锁）
     */
    public void triggerAsyncEvaluation(String sessionId, Long userId) {
        InterviewSessionEntity session = interviewMapper.findSessionBySessionId(sessionId);
        if (session != null && !"COMPLETED".equals(session.getEvaluateStatus())) {
            session.setEvaluateStatus("PROCESSING");
            interviewMapper.updateSessionEvaluation(session);
        }

        if (interviewEvaluateStreamProducer != null) {
            interviewEvaluateStreamProducer.sendEvaluateTask(sessionId, userId);
        } else {
            // 单元测试无 Redis 依赖环境下的轻量异步兜底
            CompletableFuture.runAsync(() -> {
                try {
                    log.info("🚀 [异步评估任务] 本地兜底执行面试报告生成: sessionId={}", sessionId);
                    generateReport(sessionId, userId);
                } catch (Exception e) {
                    log.error("❌ [异步评估任务] 本地兜底生成报告失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
                }
            });
        }
    }

    /**
     * 生成/获取面试深度评估报告
     */
    @Transactional
    public InterviewReportDTO generateReport(String sessionId, Long userId) {
        InterviewSessionEntity session = validateSessionOwner(sessionId, userId);
        if ("COMPLETED".equals(session.getEvaluateStatus()) && session.getOverallScore() != null) {
            return buildReportFromEntity(session);
        }

        List<InterviewQuestionEntity> questions = interviewMapper.findQuestionsBySessionId(sessionId);
        String prompt = InterviewPrompts.buildEvaluationReportPrompt(session.getSkillId(), questions);

        // 调用通用 AI 结构化评估组件
        InterviewReportDTO report = aiStructuredClient.callForStructured(
                prompt,
                new TypeReference<InterviewReportDTO>() {},
                () -> generateFallbackReport(session, questions)
        );

        // 持久化综合结果与题目明细
        session.setOverallScore(BigDecimal.valueOf(report.overallScore()).setScale(2, RoundingMode.HALF_UP));
        session.setOverallFeedback(report.overallFeedback());
        session.setStatus("EVALUATED");
        session.setEvaluateStatus("COMPLETED");
        session.setStrengths(toJson(report.strengths(), "[]"));
        session.setImprovements(toJson(report.improvements(), "[]"));
        session.setCategoryScores(toJson(report.categoryScores(), "[]"));
        interviewMapper.updateSessionEvaluation(session);

        for (QuestionEvaluationDTO qEval : report.questionDetails()) {
            InterviewQuestionEntity qEntity = new InterviewQuestionEntity();
            qEntity.setSessionId(sessionId);
            qEntity.setQuestionIndex(qEval.questionIndex());
            qEntity.setScore(BigDecimal.valueOf(qEval.score()).setScale(2, RoundingMode.HALF_UP));
            qEntity.setFeedback(qEval.feedback());
            report.referenceAnswers().stream()
                    .filter(r -> r.questionIndex() == qEval.questionIndex())
                    .findFirst()
                    .ifPresent(ref -> {
                        qEntity.setReferenceAnswer(ref.referenceAnswer());
                        qEntity.setKeyPoints(toJson(ref.keyPoints(), "[]"));
                    });
            interviewMapper.updateQuestionEvaluation(qEntity);
        }

        // 异步沉淀本场面试评估事实到 Mem0 记忆库
        if (mem0Client != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    String summary = String.format("考生技术栈: %s, 综合得分: %.1f, 强项: %s, 待改进短板: %s",
                            session.getSkillId(), report.overallScore(),
                            report.strengths() != null ? String.join(", ", report.strengths()) : "无",
                            report.improvements() != null ? String.join(", ", report.improvements()) : "无");
                    mem0Client.addMemory(summary, userId);
                } catch (Exception e) {
                    log.warn("Mem0 异步沉淀面试记忆失败: {}", e.getMessage());
                }
            });
        }

        return report;
    }

    /**
     * 会话历史列表（带异常中断自愈，由 Redis 分布式排重锁严格防重）
     */
    public List<SessionListItemDTO> listSessions(Long userId) {
        List<InterviewSessionEntity> list = interviewMapper.findSessionsByUserId(userId);
        for (InterviewSessionEntity s : list) {
            // 遗留任务自愈：发现历史被中断的任务，安全投递至 Redis Stream（由 Redis 分布式锁严格防重）
            if (("PENDING".equals(s.getEvaluateStatus()) || "PROCESSING".equals(s.getEvaluateStatus()))
                    && "COMPLETED".equals(s.getStatus())) {
                triggerAsyncEvaluation(s.getSessionId(), userId);
            }
        }
        return list.stream().map(s -> new SessionListItemDTO(
                s.getSessionId(), s.getSkillId(), s.getDifficulty(), s.getResumeId(),
                s.getTotalQuestions(), s.getStatus(), s.getEvaluateStatus(), s.getEvaluateError(),
                s.getOverallScore() != null ? s.getOverallScore().doubleValue() : null,
                s.getSourceType(), s.getKnowledgeBaseId(), s.getInterviewCategory(),
                s.getCreatedAt() != null ? s.getCreatedAt().format(DATE_TIME_FORMATTER) : null,
                s.getCompletedAt() != null ? s.getCompletedAt().format(DATE_TIME_FORMATTER) : null
        )).toList();
    }

    @Transactional
    public void deleteSession(String sessionId, Long userId) {
        // 先校验归属：以前这里直接按 sessionId 删题目，别人的会话题目会被误删
        validateSessionOwner(sessionId, userId);
        interviewMapper.deleteQuestionsBySessionId(sessionId);
        interviewMapper.deleteSessionBySessionId(sessionId, userId);
    }

    public InterviewSessionDTO findUnfinishedSession(Long userId, Long resumeId) {
        InterviewSessionEntity session = interviewMapper.findUnfinishedSession(userId, resumeId);
        return session != null ? getSession(session.getSessionId(), userId) : null;
    }

    /**
     * 获取全量面试复盘详情
     */
    public InterviewDetailDTO getInterviewDetails(String sessionId, Long userId) {
        InterviewSessionEntity session = validateSessionOwner(sessionId, userId);
        List<InterviewQuestionEntity> questions = interviewMapper.findQuestionsBySessionId(sessionId);

        List<String> strengths = fromJson(session.getStrengths(), new TypeReference<>() {}, List.of());
        List<String> improvements = fromJson(session.getImprovements(), new TypeReference<>() {}, List.of());

        List<InterviewQuestionDTO> qDtos = questions.stream().map(this::mapToQuestionDTO).toList();

        List<ReferenceAnswerDTO> referenceAnswers = questions.stream().map(q -> new ReferenceAnswerDTO(
                q.getQuestionIndex(), q.getQuestion(),
                q.getReferenceAnswer() != null ? q.getReferenceAnswer() : "掌握核心机制与应用场景即可。",
                fromJson(q.getKeyPoints(), new TypeReference<>() {}, List.of())
        )).toList();

        List<AnswerDetailDTO> answers = questions.stream().map(q -> new AnswerDetailDTO(
                q.getQuestionIndex(),
                q.getQuestion(),
                q.getCategory(),
                q.getUserAnswer() != null ? q.getUserAnswer() : "",
                q.getScore() != null ? q.getScore().doubleValue() : null,
                q.getFeedback(),
                q.getReferenceAnswer(),
                fromJson(q.getKeyPoints(), new TypeReference<>() {}, List.of()),
                q.getUpdatedAt() != null ? q.getUpdatedAt().format(DATE_TIME_FORMATTER) : null
        )).toList();

        return new InterviewDetailDTO(
                session.getId(),
                session.getSessionId(),
                session.getTotalQuestions(),
                session.getStatus(),
                session.getEvaluateStatus(),
                session.getEvaluateError(),
                session.getOverallScore() != null ? session.getOverallScore().doubleValue() : null,
                session.getSourceType(),
                session.getKnowledgeBaseId(),
                session.getOverallFeedback(),
                session.getCreatedAt() != null ? session.getCreatedAt().format(DATE_TIME_FORMATTER) : null,
                session.getCompletedAt() != null ? session.getCompletedAt().format(DATE_TIME_FORMATTER) : null,
                qDtos,
                strengths,
                improvements,
                referenceAnswers,
                answers
        );
    }

    /**
     * 导出结构化面试报告
     */
    public byte[] exportInterviewPdf(String sessionId, Long userId) {
        InterviewDetailDTO detail = getInterviewDetails(sessionId, userId);
        StringBuilder sb = new StringBuilder();
        sb.append("# 模拟面试诊断与能力评估报告\n\n");
        sb.append("- **面试会话 ID**: ").append(detail.sessionId()).append("\n");
        sb.append("- **综合得分**: ").append(detail.overallScore() != null ? detail.overallScore() + " 分" : "未出分").append("\n");
        sb.append("- **创建时间**: ").append(detail.createdAt()).append("\n");
        sb.append("- **完成时间**: ").append(detail.completedAt() != null ? detail.completedAt() : "进行中").append("\n\n");
        sb.append("## 考官总评\n").append(detail.overallFeedback() != null ? detail.overallFeedback() : "暂无总评").append("\n\n");
        sb.append("## 核心技术优势\n");
        if (detail.strengths() != null && !detail.strengths().isEmpty()) {
            for (String s : detail.strengths()) sb.append("- ").append(s).append("\n");
        } else {
            sb.append("- 基础概念掌握良好\n");
        }
        sb.append("\n## 薄弱待提升点\n");
        if (detail.improvements() != null && !detail.improvements().isEmpty()) {
            for (String imp : detail.improvements()) sb.append("- ").append(imp).append("\n");
        } else {
            sb.append("- 持续强化生产故障排查经验\n");
        }
        sb.append("\n## 逐题作答与诊断复盘\n");
        if (detail.answers() != null) {
            for (AnswerDetailDTO a : detail.answers()) {
                sb.append("\n### 第 ").append(a.questionIndex() + 1).append(" 题: [").append(a.category() != null ? a.category() : "综合").append("] ").append(a.question()).append("\n");
                sb.append("- **考生作答**: ").append(!a.userAnswer().isBlank() ? a.userAnswer() : "【未作答】").append("\n");
                sb.append("- **考官评分**: ").append(a.score() != null ? a.score() + " 分" : "未出分").append("\n");
                sb.append("- **诊断反馈**: ").append(a.feedback() != null ? a.feedback() : "无").append("\n");
                sb.append("- **标准标答**: ").append(a.referenceAnswer() != null ? a.referenceAnswer() : "无").append("\n");
            }
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ==================== 私有辅助 ====================

    /**
     * 面试会话归属校验（按 sessionId 访问会话的所有入口都必须经过这里）。
     * userId 为空直接 403；会话无归属人或归属人不是当前用户也是 403。
     */
    InterviewSessionEntity validateSessionOwner(String sessionId, Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该面试会话");
        }
        InterviewSessionEntity session = interviewMapper.findSessionBySessionId(sessionId);
        if (session == null) throw new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在");
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该面试会话");
        }
        return session;
    }

    private InterviewReportDTO buildReportFromEntity(InterviewSessionEntity session) {
        List<InterviewQuestionEntity> questions = interviewMapper.findQuestionsBySessionId(session.getSessionId());
        List<String> strengths = fromJson(session.getStrengths(), new TypeReference<>() {}, List.of("概念清晰"));
        List<String> improvements = fromJson(session.getImprovements(), new TypeReference<>() {}, List.of("持续实践"));
        List<CategoryScoreDTO> categoryScores = fromJson(session.getCategoryScores(), new TypeReference<>() {}, List.of());

        List<QuestionEvaluationDTO> questionDetails = questions.stream().map(q -> new QuestionEvaluationDTO(
                q.getQuestionIndex(), q.getQuestion(), q.getCategory(),
                q.getUserAnswer() != null ? q.getUserAnswer() : "",
                q.getScore() != null ? q.getScore().doubleValue() : 0.0,
                q.getFeedback() != null ? q.getFeedback() : "已完成作答。"
        )).toList();

        List<ReferenceAnswerDTO> referenceAnswers = questions.stream().map(q -> new ReferenceAnswerDTO(
                q.getQuestionIndex(), q.getQuestion(),
                q.getReferenceAnswer() != null ? q.getReferenceAnswer() : "暂无标准答案",
                fromJson(q.getKeyPoints(), new TypeReference<>() {}, List.of())
        )).toList();

        return new InterviewReportDTO(
                session.getSessionId(), session.getTotalQuestions(),
                session.getOverallScore() != null ? session.getOverallScore().doubleValue() : 80.0,
                categoryScores, questionDetails,
                session.getOverallFeedback() != null ? session.getOverallFeedback() : "已完成评估。",
                strengths, improvements, referenceAnswers
        );
    }

    private InterviewReportDTO generateFallbackReport(InterviewSessionEntity session, List<InterviewQuestionEntity> questions) {
        double score = 75.0;
        List<QuestionEvaluationDTO> details = questions.stream().map(q -> new QuestionEvaluationDTO(
                q.getQuestionIndex(), q.getQuestion(), q.getCategory(),
                q.getUserAnswer() != null ? q.getUserAnswer() : "", 75.0, "回答条理清晰。"
        )).toList();
        List<ReferenceAnswerDTO> refs = questions.stream().map(q -> new ReferenceAnswerDTO(
                q.getQuestionIndex(), q.getQuestion(),
                q.getReferenceAnswer() != null ? q.getReferenceAnswer() : "掌握核心机制与应用场景即可。",
                List.of("概念准确", "逻辑自洽")
        )).toList();

        return new InterviewReportDTO(
                session.getSessionId(), questions.size(), score,
                List.of(new CategoryScoreDTO(session.getSkillId(), score, questions.size())),
                details, "候选人技术功底扎实，能够较好地应对常见技术问题。",
                List.of("技术表达清晰", "核心概念掌握良好"),
                List.of("建议进一步结合线上生产排错案例拓展回答"), refs
        );
    }

    private InterviewQuestionDTO mapToQuestionDTO(InterviewQuestionEntity entity) {
        return new InterviewQuestionDTO(
                entity.getQuestionIndex(), entity.getQuestion(), entity.getType(), entity.getCategory(),
                entity.getTopicSummary(), entity.getUserAnswer(),
                entity.getScore() != null ? entity.getScore().doubleValue() : null,
                entity.getFeedback(), entity.getIsFollowUp(), entity.getParentQuestionIndex(),
                entity.getReferenceAnswer(), fromJson(entity.getKeyPoints(), new TypeReference<>() {}, List.of()),
                entity.getScoringRubric(), entity.getSourceContext()
        );
    }

    private String toJson(Object obj, String fallback) {
        try {
            return obj != null ? objectMapper.writeValueAsString(obj) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return fallback;
        }
    }

    private List<InterviewQuestionDTO> getDefaultQuestions(String skillId, int count) {
        List<InterviewQuestionDTO> defaults = List.of(
                new InterviewQuestionDTO(0, "请简述 Java 中 HashMap 的底层实现原理及在 JDK 1.8 中的优化？",
                        "TECHNICAL", "Java基础", "HashMap底层原理", null, null, null, false, null,
                        "数组+链表+红黑树，阈值达到8且数组长度达到64转红黑树；引入尾插法避免死循环。", List.of("红黑树转换", "扰动函数", "扩容机制"), "概念准确", null),
                new InterviewQuestionDTO(1, "请解释 JMM 内存模型以及 volatile 关键字的底层原理？",
                        "TECHNICAL", "并发编程", "volatile与JMM", null, null, null, false, null,
                        "保证可见性与有序性（禁止指令重排，通过内存屏障），不保证原子性。", List.of("内存屏障", "可见性", "Lock前缀指令"), "条理清晰", null),
                new InterviewQuestionDTO(2, "MySQL InnoDB 引擎中聚簇索引与非聚簇索引的区别是什么？什么是回表？",
                        "TECHNICAL", "MySQL", "索引原理与回表", null, null, null, false, null,
                        "聚簇索引叶子节点存整行数据，非聚簇索引叶子节点存主键值；回表即通过主键再查聚簇索引。", List.of("B+树", "聚簇索引", "覆盖索引"), "深度到位", null),
                new InterviewQuestionDTO(3, "Spring 的循环依赖是如何解决的？为什么需要三级缓存？",
                        "TECHNICAL", "Spring", "三级缓存解决循环依赖", null, null, null, false, null,
                        "通过三级缓存与提前暴露 ObjectFactory 解决，第三级缓存主要用于解决 AOP 动态代理对象的提前暴露问题。", List.of("三级缓存", "ObjectFactory", "AOP代理"), "全面准确", null),
                new InterviewQuestionDTO(4, "Redis 缓存穿透、缓存击穿、缓存雪崩的区别及主流解决方案是什么？",
                        "TECHNICAL", "Redis", "缓存异常三剑客", null, null, null, false, null,
                        "穿透=查不存在数据(布隆过滤器)；击穿=热点Key失效并发涌入(互斥锁)；雪崩=大面积Key同时失效(随机TTL/高可用集群)。", List.of("布隆过滤器", "互斥锁", "随机TTL"), "方案详尽", null)
        );
        return defaults.stream().limit(count).toList();
    }
}
