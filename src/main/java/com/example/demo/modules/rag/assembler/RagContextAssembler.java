package com.example.demo.modules.rag.assembler;

import com.example.demo.modules.rag.model.RagDocument;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 📝 上下文组装与抗幻觉约束增强器
 */
@Component
public class RagContextAssembler {

    private static final String RAG_SYSTEM_PROMPT_TEMPLATE = """
            你是一个严谨客观的大厂资深技术专家。你正在参加一场严格的“闭卷事实问答考核”。
            
            【最高回答准则（违反将严重扣分）】：
            1. 【100% 事实锚定】：你的所有技术论断与回答，必须 100% 能在下方的【知识库参考切片】中找到直接证据；
            2. 【严禁自由发挥】：切片中未明确提及的拓展知识（如周边类库、历史背景、额外参数），严禁凭自身记忆擅自扩充，严禁脑补；
            3. 【带引用角标作答】：在回答中的每一个核心技术论断句尾，必须明确标注引用切片编号，例如：“AQS 底层维护一个 volatile int state 变量[切片1]。”
            4. 【坦诚边界】：若切片中确实未包含某个被问及的技术细节，直接明确声明“参考切片未提及此部分细节”。
            
            ==================== 📚 知识库参考切片 ====================
            %s
            ==========================================================
            """;

    /**
     * 将切片列表组装为结构化的上下文引用文本
     */
    public String assembleContextText(List<RagDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "（未检索到相关知识库切片）";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            RagDocument doc = documents.get(i);
            sb.append(String.format("【切片 %d (综合分: %.2f)】\n%s\n\n",
                    (i + 1),
                    doc.getFinalScore() != null ? doc.getFinalScore() : 0.0,
                    doc.getText() != null ? doc.getText().trim() : ""
            ));
        }
        return sb.toString().trim();
    }

    /**
     * 组装带抗幻觉指令的完整 System Prompt
     */
    public String assembleSystemPrompt(List<RagDocument> documents) {
        String contextText = assembleContextText(documents);
        return String.format(RAG_SYSTEM_PROMPT_TEMPLATE, contextText);
    }
}