package com.example.demo.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 🛡️ Prompt 注入与越狱无害化净化器 (Prompt Sanitizer)
 * <p>
 * 提供三大核心防御机制：
 * 1. 行首角色标记伪造防御 (Role Marker Sanitization)；
 * 2. 常见越狱指令与规则重置短语抹除 (Instruction Reset Sanitization)；
 * 3. 动态不可预测的随机 UUID 边界包裹 (Dynamic Boundary Wrapping)。
 */
@Slf4j
@Component
public class PromptSanitizer {

    // 行首角色标记伪造：只匹配行首，防止普通文本中出现 "system design" 被误杀
    private static final Pattern ROLE_INJECTION_PATTERN = Pattern.compile(
            "(?im)^\\s*(system|user|assistant|human|ai|model)\\s*[:：].*"
    );

    // 经典越狱/注入短语库
    private static final Pattern INJECTION_PHRASE_PATTERN = Pattern.compile(
            "(ignore\\s+(all\\s+|previous\\s+|above\\s+|your\\s+)*(instructions|prompts|rules))" +
            "|(forget\\s+(everything|all\\s*(previous\\s*)?(instructions|rules|prompts)))" +
            "|(new\\s+instructions?:)" +
            "|(act\\s+as\\s+(an?\\s+)?(hacker|unrestricted|developer|dan))" +
            "|忽略[以之]?前?[的所]?有?(指令|规则|提示词)?" +
            "|忘记[以之]?前?[的所]?有?(指令|规则|提示词)?" +
            "|你不再是" +
            "|你的新角色是" +
            "|进入开发者模式" +
            "|DAN模式",
            Pattern.CASE_INSENSITIVE
    );

    // 伪造分隔符防御
    private static final Pattern DELIMITER_INJECTION_PATTERN = Pattern.compile(
            "---(?:简历|文档|问答|知识库|系统)内容?(?:开始|结束)---",
            Pattern.CASE_INSENSITIVE
    );

    // 伪造 XML/HTML 边界闭合标签防御
    private static final Pattern BOUNDARY_TAG_PATTERN = Pattern.compile(
            "</?data-boundary[^>]*>",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 清洗文本中的潜在恶意注入指令，替换为中性安全标记
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        boolean injected = false;
        String result = text;

        var roleMatcher = ROLE_INJECTION_PATTERN.matcher(result);
        if (roleMatcher.find()) {
            injected = true;
            result = roleMatcher.replaceAll("[filtered-role-marker]");
        }

        var phraseMatcher = INJECTION_PHRASE_PATTERN.matcher(result);
        if (phraseMatcher.find()) {
            injected = true;
            result = phraseMatcher.replaceAll("[filtered-injection]");
        }

        var delimMatcher = DELIMITER_INJECTION_PATTERN.matcher(result);
        if (delimMatcher.find()) {
            result = delimMatcher.replaceAll("[filtered-delimiter]");
        }

        var tagMatcher = BOUNDARY_TAG_PATTERN.matcher(result);
        if (tagMatcher.find()) {
            result = tagMatcher.replaceAll("[filtered-boundary-tag]");
        }

        if (injected) {
            log.warn("🚨 [Prompt安全防御] 捕获潜在的越狱/提示词注入攻击，文本长度: {}", text.length());
        }
        return result;
    }

    /**
     * 使用不可预测的随机 UUID 动态边界包裹外部数据。
     * <p>
     * 格式：{@code <data-boundary-{uuid}-{label}> ... </data-boundary-{uuid}-{label}>}
     * 攻击者在输入阶段无法获知当次生成的 8 位 UUID，物理上无法提前闭合标签进行边界逃逸！
     */
    public String wrapWithBoundary(String label, String text) {
        if (text == null) {
            text = "";
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String openTag = "<data-boundary-" + id + "-" + label + ">";
        String closeTag = "</data-boundary-" + id + "-" + label + ">";
        return openTag + "\n" + sanitize(text) + "\n" + closeTag;
    }

    /**
     * 检测文本是否包含注入尝试（仅用于监控审计，不阻塞业务）
     */
    public boolean detectInjectionAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return ROLE_INJECTION_PATTERN.matcher(text).find()
                || INJECTION_PHRASE_PATTERN.matcher(text).find();
    }
}