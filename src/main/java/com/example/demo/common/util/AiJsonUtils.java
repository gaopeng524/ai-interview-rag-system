package com.example.demo.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 大模型输出 JSON 智能提取与解析工具类
 */
@Slf4j
public final class AiJsonUtils {

    private AiJsonUtils() {}

    /**
     * 智能从大模型响应中清洗并提取纯净的 JSON 字符串（防御 Markdown 标记与闲聊文字）
     */
    public static String extractJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "{}";
        }

        String trimmed = rawResponse.trim();

        // 1. 优先提取 ```json ... ``` 块
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.lastIndexOf("```");
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        } else if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            int end = trimmed.lastIndexOf("```");
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // 2. 若无 markdown 围栏，自动定位最外层 { ... } 或 [ ... ]
        int firstObj = trimmed.indexOf('{');
        int lastObj = trimmed.lastIndexOf('}');
        int firstArr = trimmed.indexOf('[');
        int lastArr = trimmed.lastIndexOf(']');

        // 判断是 JSON 数组还是 JSON 对象
        if (firstArr != -1 && lastArr > firstArr && (firstObj == -1 || firstArr < firstObj)) {
            return trimmed.substring(firstArr, lastArr + 1).trim();
        }

        if (firstObj != -1 && lastObj > firstObj) {
            return trimmed.substring(firstObj, lastObj + 1).trim();
        }

        // 3. 若通篇完全不含任何 JSON 结构符号，抛出明确诊断异常以触发调用方安全兜底
        throw new IllegalArgumentException("大模型未返回合法 JSON 结构（未检测到任何 '{' 或 '[' 符号），原始输出预览: " +
                (trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed));
    }

    /**
     * 提取并反序列化为指定类型对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T parse(String rawResponse, TypeReference<T> typeRef, ObjectMapper objectMapper) throws Exception {
        if (typeRef != null && String.class.equals(typeRef.getType())) {
            return (T) (rawResponse != null ? rawResponse.trim() : "");
        }
        String cleanJson = extractJson(rawResponse);
        return objectMapper.readValue(cleanJson, typeRef);
    }

    /**
     * 提取并反序列化为指定 Class 对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T parse(String rawResponse, Class<T> clazz, ObjectMapper objectMapper) throws Exception {
        if (String.class.equals(clazz)) {
            return (T) (rawResponse != null ? rawResponse.trim() : "");
        }
        String cleanJson = extractJson(rawResponse);
        return objectMapper.readValue(cleanJson, clazz);
    }
}
