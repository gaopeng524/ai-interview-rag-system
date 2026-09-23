package com.example.demo.modules.knowledgebase.service;

import com.example.demo.common.ai.AiStructuredClient;
import com.example.demo.common.constant.AsyncTaskStreamConstants;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseQuestionMapper;
import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseDTO;
import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseQuestionDTO;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBaseQuestionEntity;
import com.example.demo.modules.knowledgebase.model.enums.KnowledgeBaseQuestionStatus;
import com.example.demo.modules.knowledgebase.model.enums.QuestionGenStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 知识库/面经智能提炼考题服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQuestionGenerationService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQuestionMapper questionMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AiStructuredClient aiStructuredClient;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;

    // 内存任务状态缓存
    private final Map<Long, KnowledgeBaseQuestionDTO.QuestionGenStatusResponse> taskStatusMap = new ConcurrentHashMap<>();

    private static final String QUESTION_GEN_PROMPT = """
            你是一个资深技术面试官与出题专家。请根据以下提供的知识库/面经文档内容，系统化地提炼并设计 %d 道高频结构化面试考题。
            目标难度设定为: %s。

            【出题要求】：
            1. 考题应紧扣提供的文档内容，覆盖不同的核心技术点/面试方向（如"Java并发"、"Redis缓存"、"MySQL索引"、"系统设计"、"Spring框架"等）；
            2. 每道考题需包含：
               - category: 题目所属分类/面试方向（如：Java并发、微服务、中间件等）；
               - difficulty: 难度等级（EASY / MEDIUM / HARD）；
               - type: 题目类型（TECHNICAL / SYSTEM_DESIGN / SCENARIO / BEHAVIORAL）；
               - question: 考题完整题干；
               - topicSummary: 一句话考点摘要；
               - referenceAnswer: 详尽的标准参考答案；
               - keyPoints: 核心考察要点与得分点列表（字符串数组）；
               - scoringRubric: 评分规则说明；
               - followUps: 1-2个递进式追问点（包含 question 追问题干、referenceAnswer 参考回答、keyPoints 追问要点）。

            【知识库/面经参考材料】：
            %s

            【输出格式要求】：
            请直接输出严格合法的 JSON 对象，不要包含 markdown 代码块包裹，结构如下：
            {
              "questions": [
                {
                  "category": "Java并发",
                  "difficulty": "MEDIUM",
                  "type": "TECHNICAL",
                  "question": "请解释 AQS (AbstractQueuedSynchronizer) 的底层核心原理及其在 ReentrantLock 中的实现？",
                  "topicSummary": "AQS核心架构与独占/共享模式",
                  "referenceAnswer": "AQS是Java并发包的基础框架，底层维护一个volatile int state变量和一个双向FIFO队列...",
                  "keyPoints": ["state状态变量与CAS修改", "Node双向队列与自旋阻塞", "独占模式tryAcquire与共享模式tryAcquireShared的区别"],
                  "scoringRubric": "完整讲出state与队列流转得满分，能结合ReentrantLock非公平锁插队机制加分",
                  "followUps": [
                    {
                      "question": "如果线程在等待队列中被中断，AQS 是如何处理中断标志与节点出队的？",
                      "referenceAnswer": "AQS 在 acquireQueued 中检测到中断会记录 interrupted标志，在获取锁后补发中断响应...",
                      "keyPoints": ["acquireQueued中断捕获", "cancelAcquire节点取消与链表调整"]
                    }
                  ]
                }
              ]
            }
            """;

    /**
     * 获取指定知识库的出题状态
     */
    public KnowledgeBaseQuestionDTO.QuestionGenStatusResponse getStatus(Long kbId) {
        return taskStatusMap.computeIfAbsent(kbId, id ->
                KnowledgeBaseQuestionDTO.QuestionGenStatusResponse.init(id, questionMapper.countByKbId(id)));
    }

    /**
     * 提交异步出题任务（带防重复提交与 Redisson 分布式排重锁）
     */
    public KnowledgeBaseQuestionDTO.QuestionGenStatusResponse submitTask(
            Long kbId,
            KnowledgeBaseQuestionDTO.GenerateQuestionsRequest request) {

        KnowledgeBase kb = knowledgeBaseMapper.findById(kbId);
        if (kb == null) {
            throw new RuntimeException("知识库不存在: " + kbId);
        }

        // 1. 内存状态防重校验：如果当前知识库已处于 PROCESSING 状态，直接返回现有状态，拒绝重复启动
        KnowledgeBaseQuestionDTO.QuestionGenStatusResponse existingStatus = taskStatusMap.get(kbId);
        if (existingStatus != null && existingStatus.questionGenStatus() == QuestionGenStatus.PROCESSING) {
            log.warn("⚠️ [知识库出题] 考题提炼任务已在执行中，拒绝重复提交: kbId={}", kbId);
            return existingStatus;
        }

        // 2. Redisson 工业级分布式排重锁（0 秒非阻塞等待，Watchdog 看门狗自动续期）
        String lockKey = AsyncTaskStreamConstants.LOCK_KB_QUESTION_GEN_PREFIX + kbId;
        RLock lock = (redissonClient != null) ? redissonClient.getLock(lockKey) : null;
        if (lock != null) {
            try {
                if (!lock.tryLock(0, -1, TimeUnit.SECONDS)) {
                    log.warn("⚠️ [知识库出题] 获取分布式排重锁失败（已有任务正在处理）: kbId={}", kbId);
                    return getStatus(kbId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Redisson 加锁中断: kbId={}", kbId);
                return getStatus(kbId);
            }
        }

        String taskId = UUID.randomUUID().toString();
        int targetCount = (request != null && request.questionCount() != null && request.questionCount() > 0)
                ? request.questionCount() : 10;
        String difficulty = (request != null && request.difficulty() != null && !request.difficulty().isBlank())
                ? request.difficulty() : "MEDIUM";

        KnowledgeBaseQuestionDTO.GenerationConfig config = KnowledgeBaseQuestionDTO.GenerationConfig.of(difficulty, targetCount, request);
        KnowledgeBaseQuestionDTO.QuestionGenStatusResponse status = KnowledgeBaseQuestionDTO.QuestionGenStatusResponse.processing(kbId, taskId, config);
        taskStatusMap.put(kbId, status);

        // 异步执行提炼
        CompletableFuture.runAsync(() -> {
            try {
                executeGeneration(kbId, taskId, targetCount, difficulty);
            } catch (Exception e) {
                log.error("❌ 知识库考题提炼失败: kbId={}, error={}", kbId, e.getMessage(), e);
                taskStatusMap.put(kbId, KnowledgeBaseQuestionDTO.QuestionGenStatusResponse.failed(kbId, taskId, config, e.getMessage()));
            } finally {
                // 任务结束（无论成功或失败），安全释放 Redisson 分布式排重锁
                if (lock != null) {
                    try {
                        if (lock.isLocked()) {
                            lock.forceUnlock();
                        }
                        log.debug("🔓 [知识库出题] 已安全释放 Redisson 分布式排重锁: lockKey={}", lockKey);
                    } catch (Exception ex) {
                        log.warn("释放知识库出题分布式锁异常: {}", ex.getMessage());
                    }
                }
            }
        });

        return status;
    }

    /**
     * 执行大模型考题提炼与持久化
     */
    @Transactional
    public void executeGeneration(Long kbId, String taskId, int targetCount, String difficulty) {
        log.info("🚀 [知识库出题任务] 开始执行面经题目提炼: kbId={}, count={}, difficulty={}", kbId, targetCount, difficulty);

        // 1. 获取知识库检索上下文内容
        List<KnowledgeBaseDTO.SearchResultItem> chunks = knowledgeBaseService.searchSemantic(
                new KnowledgeBaseDTO.SearchRequest("核心面试要点 架构设计 算法与常见技术问答", List.of(kbId), 15)
        );

        StringBuilder contextBuilder = new StringBuilder();
        if (chunks != null && !chunks.isEmpty()) {
            for (var c : chunks) {
                if (c.text() != null) {
                    contextBuilder.append(c.text()).append("\n\n");
                }
            }
        }

        KnowledgeBase kb = knowledgeBaseMapper.findById(kbId);
        if (contextBuilder.length() < 100 && kb != null) {
            contextBuilder.append("知识库名称: ").append(kb.getName()).append("\n");
            contextBuilder.append("所属分类: ").append(kb.getCategory() != null ? kb.getCategory() : "通用").append("\n");
        }

        String context = contextBuilder.toString();
        if (context.length() > 6000) {
            context = context.substring(0, 6000);
        }

        // 2. 调用大模型结构化抽取
        String prompt = String.format(QUESTION_GEN_PROMPT, targetCount, difficulty, context);

        GeneratedQuestionList response = aiStructuredClient.callForStructured(
                prompt,
                new TypeReference<GeneratedQuestionList>() {},
                () -> generateFallbackQuestions(kb, targetCount, difficulty)
        );

        if (response == null || response.questions == null || response.questions.isEmpty()) {
            response = generateFallbackQuestions(kb, targetCount, difficulty);
        }

        // 3. 转换为持久化实体并落库
        List<KnowledgeBaseQuestionEntity> entities = new ArrayList<>();
        for (GeneratedQuestionItem item : response.questions) {
            if (item.question == null || item.question.isBlank()) continue;

            entities.add(KnowledgeBaseQuestionEntity.builder()
                    .knowledgeBaseId(kbId)
                    .skillId("knowledge-base")
                    .difficulty(item.difficulty != null ? item.difficulty.toUpperCase() : difficulty)
                    .type(item.type != null ? item.type : "TECHNICAL")
                    .category(item.category != null && !item.category.isBlank() ? item.category : "通用")
                    .question(item.question.trim())
                    .topicSummary(item.topicSummary)
                    .referenceAnswer(item.referenceAnswer)
                    .keyPoints(toJson(item.keyPoints))
                    .scoringRubric(item.scoringRubric)
                    .followUps(toJson(item.followUps))
                    .sourceContext(kb != null ? kb.getName() : null)
                    .status(KnowledgeBaseQuestionStatus.ACTIVE)
                    .build());
        }

        if (!entities.isEmpty()) {
            // 清理旧题目并重新写入
            questionMapper.deleteByKnowledgeBaseId(kbId);
            questionMapper.batchInsert(entities);
            log.info("✅ [知识库出题任务] 成功生成并保存 {} 道考题: kbId={}", entities.size(), kbId);
        }

        // 4. 更新任务状态为 COMPLETED
        KnowledgeBaseQuestionDTO.GenerationConfig completedConfig = KnowledgeBaseQuestionDTO.GenerationConfig.of(difficulty, targetCount, null);
        taskStatusMap.put(kbId, KnowledgeBaseQuestionDTO.QuestionGenStatusResponse.completed(kbId, taskId, completedConfig, entities.size()));
    }

    private GeneratedQuestionList generateFallbackQuestions(KnowledgeBase kb, int count, String difficulty) {
        String kbName = kb != null ? kb.getName() : "技术面经";
        List<GeneratedQuestionItem> list = new ArrayList<>();
        list.add(new GeneratedQuestionItem(
                "核心架构",
                difficulty,
                "TECHNICAL",
                "请结合 " + kbName + " 中的核心设计，详细阐述系统的整体架构与关键模块交互逻辑？",
                "系统架构与模块划分",
                "系统分为接入层、业务逻辑层与数据持久层，通过解耦设计与异步消息保障高可用...",
                List.of("核心分层设计", "高并发吞吐保障", "容灾与降级策略"),
                "能够完整条理清晰阐明架构分层得满分",
                List.of(new GeneratedFollowUp("如果系统遇到流量突增，你有哪些限流与排队方案？", "可采用令牌桶、Redis分布式限流与消息队列平峰...", List.of("限流算法", "消息削峰")))
        ));
        return new GeneratedQuestionList(list);
    }

    private String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    public record GeneratedQuestionList(List<GeneratedQuestionItem> questions) {}

    public record GeneratedQuestionItem(
            String category,
            String difficulty,
            String type,
            String question,
            String topicSummary,
            String referenceAnswer,
            List<String> keyPoints,
            String scoringRubric,
            List<GeneratedFollowUp> followUps
    ) {}

    public record GeneratedFollowUp(
            String question,
            String referenceAnswer,
            List<String> keyPoints
    ) {}
}
