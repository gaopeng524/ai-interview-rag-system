package com.example.demo.modules.interview.service;

import com.example.demo.common.ai.AiStructuredClient;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.interview.model.dto.InterviewDTO.*;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 预设面试技能/方向与岗位 JD 智能解析服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSkillService {

    public static final String CUSTOM_SKILL_ID = "custom";
    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$");

    private final AiStructuredClient aiStructuredClient;
    private final Map<String, SkillDTO> skillRegistry = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        loadSkillsFromClasspath();
    }

    public void loadSkillsFromClasspath() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
            Yaml yaml = new Yaml();

            for (Resource res : resources) {
                String uriStr = res.getURI().toString().replace("\\", "/");
                String[] parts = uriStr.split("/skills/");
                if (parts.length < 2) continue;
                String sub = parts[1];
                String skillId = sub.substring(0, sub.indexOf("/"));
                if (skillId.startsWith("_")) continue;

                // 读取 SKILL.md
                String content = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String name = skillId;
                String description = "";
                String persona = "";

                Matcher m = FRONT_MATTER_PATTERN.matcher(content);
                if (m.find()) {
                    String frontMatter = m.group(1);
                    persona = m.group(2).trim();
                    try {
                        Map<String, Object> fm = yaml.load(frontMatter);
                        if (fm != null) {
                            if (fm.get("name") != null) name = fm.get("name").toString();
                            if (fm.get("description") != null) description = fm.get("description").toString();
                        }
                    } catch (Exception ignored) {}
                }

                // 读取 skill.meta.yml
                String displayName = name;
                DisplayDTO display = new DisplayDTO("💼", "from-blue-500 to-indigo-500", "bg-blue-100 dark:bg-blue-900/30", "text-blue-600 dark:text-blue-400");
                List<CategoryDTO> categories = new ArrayList<>();

                try {
                    Resource metaRes = resolver.getResource("classpath:skills/" + skillId + "/skill.meta.yml");
                    if (metaRes.exists()) {
                        try (InputStream is = metaRes.getInputStream()) {
                            Map<String, Object> metaMap = yaml.load(is);
                            if (metaMap != null) {
                                if (metaMap.get("displayName") != null) displayName = metaMap.get("displayName").toString();
                                if (metaMap.get("display") instanceof Map<?, ?> dMap) {
                                    display = new DisplayDTO(
                                            Objects.toString(dMap.get("icon"), "💼"),
                                            Objects.toString(dMap.get("gradient"), "from-blue-500 to-indigo-500"),
                                            Objects.toString(dMap.get("iconBg"), "bg-blue-100"),
                                            Objects.toString(dMap.get("iconColor"), "text-blue-600")
                                    );
                                }
                                if (metaMap.get("categories") instanceof List<?> cList) {
                                    for (Object cObj : cList) {
                                        if (cObj instanceof Map<?, ?> cMap) {
                                            categories.add(new CategoryDTO(
                                                    Objects.toString(cMap.get("key"), ""),
                                                    Objects.toString(cMap.get("label"), ""),
                                                    Objects.toString(cMap.get("priority"), "NORMAL"),
                                                    Objects.toString(cMap.get("ref"), null),
                                                    Boolean.TRUE.equals(cMap.get("shared"))
                                            ));
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("读取 skill.meta.yml 失败: skillId={}, error={}", skillId, e.getMessage());
                }

                SkillDTO skill = new SkillDTO(
                        skillId,
                        displayName,
                        description,
                        categories,
                        true,
                        null,
                        persona,
                        display
                );
                skillRegistry.put(skillId, skill);
                log.info("🎯 [预设面试方向加载] skillId={}, name={}, 分类数={}", skillId, displayName, categories.size());
            }
        } catch (Exception e) {
            log.error("加载预设面试方向失败: {}", e.getMessage(), e);
        }
    }

    public List<SkillDTO> getAllSkills() {
        return new ArrayList<>(skillRegistry.values());
    }

    public SkillDTO getSkill(String skillId) {
        SkillDTO skill = skillRegistry.get(skillId);
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到面试主题方向: " + skillId);
        }
        return skill;
    }

    private final Map<String, String> referenceCache = new ConcurrentHashMap<>();

    /**
     * 获取指定技能方向的考点参考内容摘要（Grounding 真实面经）
     */
    public String getSkillReferenceContext(String skillId) {
        SkillDTO skill = skillRegistry.get(skillId);
        if (skill == null || skill.categories() == null || skill.categories().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        for (CategoryDTO cat : skill.categories()) {
            if (cat.ref() == null || cat.ref().isBlank()) continue;
            String refFile = cat.ref();
            String path = Boolean.TRUE.equals(cat.shared())
                    ? "classpath:skills/_shared/references/" + refFile
                    : "classpath:skills/" + skillId + "/references/" + refFile;

            String content = referenceCache.computeIfAbsent(path, p -> {
                try {
                    Resource r = resolver.getResource(p);
                    if (r.exists()) {
                        String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        // 裁剪前 1500 字符作为精选考点提纲
                        return text.length() > 1500 ? text.substring(0, 1500) + "..." : text;
                    }
                } catch (Exception e) {
                    log.warn("读取参考考点失败: path={}, error={}", p, e.getMessage());
                }
                return "";
            });

            if (!content.isBlank()) {
                sb.append("\n### [").append(cat.label()).append(" 考点参考]:\n").append(content).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 大模型岗位 JD 结构化解析：提取技术方向考点维度
     */
    public List<CategoryDTO> parseJd(String jdText) {
        if (jdText == null || jdText.trim().length() < 30) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 内容过短（至少 30 字），请补充后重试");
        }

        String prompt = String.format("""
                你是一位资深技术招聘架构师。请深入分析以下岗位职责与要求 (Job Description)，提炼出 4~6 个核心技术考核维度。
                
                岗位 JD：
                %s
                
                请返回严格合法的 JSON 数组，每个元素包含：
                - "key": 大写英文标识（如 JAVA, MYSQL, REDIS, SPRING, SYSTEM_DESIGN, DISTRIBUTED, ARCHITECTURE 等）
                - "label": 中文分类名称（如 Java 核心, MySQL 存储与调优, 架构设计等）
                - "priority": 优先级，必须是 "CORE", "NORMAL", "ALWAYS_ONE" 之一
                
                格式示例：
                [
                  {"key": "JAVA", "label": "Java 核心与并发", "priority": "CORE"},
                  {"key": "MYSQL", "label": "MySQL 数据库与索引", "priority": "CORE"},
                  {"key": "DISTRIBUTED", "label": "分布式系统与微服务", "priority": "NORMAL"},
                  {"key": "PROJECT", "label": "项目实战与系统设计", "priority": "ALWAYS_ONE"}
                ]
                """, jdText);

        return aiStructuredClient.callForStructured(
                prompt,
                new TypeReference<List<CategoryDTO>>() {},
                () -> List.of(
                        new CategoryDTO("CORE_TECH", "核心技术能力", "CORE", null, false),
                        new CategoryDTO("DATABASE", "数据存储与性能", "CORE", null, false),
                        new CategoryDTO("SYSTEM_DESIGN", "系统设计与架构", "NORMAL", null, false),
                        new CategoryDTO("PROJECT", "项目经历与实战", "ALWAYS_ONE", null, false)
                )
        );
    }
}
