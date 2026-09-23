package com.example.demo.modules.interview.prompt;

import com.example.demo.modules.interview.model.entity.InterviewQuestionEntity;

import java.util.List;

/**
 * 模拟面试与考官评估领域 Prompt 模板集中常量类
 */
public final class InterviewPrompts {

    private InterviewPrompts() {}

    /**
     * 生成面试考题的 Prompt
     */
    public static String buildGenerateQuestionsPrompt(
            String skillId, String difficulty, int count,
            String resumeText, String jdText, String kbContext) {

        return String.format("""
                你是一位拥有10年经验的技术面试官。请针对【岗位方向: %s】、【难度级别: %s】生成 %d 道经典且具深度的面试题。
                
                %s
                %s
                %s
                
                【出题要求】
                1. 覆盖不同的核心技术维度（如原理机制、高并发、实战排错、架构取舍）；
                2. 保持精简快速，仅需返回题目必要字段，必须输出严格合法的 JSON 数组；
                3. JSON 字段要求：
                   - "questionIndex": 从 0 开始递增的整数
                   - "question": 题目描述（请提出深度好题）
                   - "type": "TECHNICAL"
                   - "category": 技术分类维度（如 Java并发、MySQL存储、Redis缓存、分布式架构等）
                   - "topicSummary": 考察的核心知识点短语
                
                请直接输出合法的 JSON 数组：
                """,
                skillId, difficulty, count,
                resumeText != null && !resumeText.isBlank() ? "【候选人简历背景】:\n" + resumeText : "",
                jdText != null && !jdText.isBlank() ? "【目标岗位要求】:\n" + jdText : "",
                kbContext != null && !kbContext.isBlank() ? "【参考面经考点】:\n" + kbContext : ""
        );
    }

    /**
     * 综合多维评估报告的 Prompt
     */
    public static String buildEvaluationReportPrompt(String skillId, List<InterviewQuestionEntity> questions) {
        StringBuilder sb = new StringBuilder();
        for (InterviewQuestionEntity q : questions) {
            sb.append(String.format("""
                    ---
                    【第 %d 题】(%s - %s)
                    题目: %s
                    标准要点: %s
                    候选人回答: %s
                    """,
                    q.getQuestionIndex() + 1, q.getCategory(), q.getTopicSummary(),
                    q.getQuestion(), q.getReferenceAnswer(),
                    (q.getUserAnswer() != null && !q.getUserAnswer().isBlank()) ? q.getUserAnswer() : "【候选人未作答】"
            ));
        }

        return String.format("""
                你是一位资深技术专家与面试主考官。请对候选人在【%s】岗位模拟面试中的所有作答进行全面、严谨、客观的综合评审。
                
                【面试作答记录】：
                %s
                
                【评估要求】：
                1. 给出 0~100 的综合得分（overallScore）；
                2. 提炼候选人的核心技术优势（strengths 数组）与薄弱待提升点（improvements 数组）；
                3. 对各技术维度进行打分（categoryScores 数组，包含 category、score、questionCount）；
                4. 对每道题给出具体的得分（0~100）和深度点评诊断反馈（feedback）；
                5. 对每道题给出标准参考答案（referenceAnswer）与采分要点（keyPoints 数组）；
                6. 给出总评寄语（overallFeedback）；
                7. 必须严格以 JSON 格式输出，不要包含任何 markdown 以外的闲聊文字。
                
                JSON 格式模版：
                {
                  "overallScore": 85.0,
                  "overallFeedback": "总评...",
                  "strengths": ["优势1", "优势2"],
                  "improvements": ["薄弱项1", "薄弱项2"],
                  "categoryScores": [
                    { "category": "Java基础", "score": 90.0, "questionCount": 2 },
                    { "category": "并发编程", "score": 80.0, "questionCount": 1 }
                  ],
                  "questionDetails": [
                    { "questionIndex": 0, "question": "...", "category": "...", "userAnswer": "...", "score": 85.0, "feedback": "点评..." }
                  ],
                  "referenceAnswers": [
                    { "questionIndex": 0, "question": "...", "referenceAnswer": "...", "keyPoints": ["要点1", "要点2"] }
                  ]
                }
                """,
                skillId, sb.toString()
        );
    }
}
