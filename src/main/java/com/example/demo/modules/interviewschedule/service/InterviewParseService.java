package com.example.demo.modules.interviewschedule.service;

import com.example.demo.common.ai.AiStructuredClient;
import com.example.demo.modules.interviewschedule.model.dto.CreateInterviewRequest;
import com.example.demo.modules.interviewschedule.model.dto.ParseResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试邀约文本智能解析服务（结合正则规则引擎 + 大模型深度语义提取）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewParseService {

    private final AiStructuredClient aiStructuredClient;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER_2 = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    // 飞书模式
    private static final Pattern TIME_PATTERN_FEISHU = Pattern.compile("(?:时间|时段)[：:]\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2})");
    private static final Pattern LINK_PATTERN_FEISHU = Pattern.compile("https://meeting\\.feishu\\.cn/[^\\s\\n]+");
    private static final Pattern COMPANY_PATTERN_FEISHU = Pattern.compile("(?:公司|单位|组织)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_FEISHU = Pattern.compile("(?:岗位|职位|职务)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern ROUND_PATTERN_FEISHU = Pattern.compile("第\\s*[一二三四五六七八九十\\d]+\\s*[轮场]");

    // 腾讯会议模式
    private static final Pattern TIME_PATTERN_TENCENT = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})\\s+(\\d{2}:\\d{2})");
    private static final Pattern MEETING_ID_PATTERN_TENCENT = Pattern.compile("(?:会议号|ID)[：:]?\\s*(\\d{9,})");
    private static final Pattern PASSWORD_PATTERN_TENCENT = Pattern.compile("密码[：:]?\\s*(\\d{4,})");
    private static final Pattern COMPANY_PATTERN_TENCENT = Pattern.compile("(?:公司|单位)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_TENCENT = Pattern.compile("(?:岗位|职位)[：:]\\s*([^\\s\\n]{1,50})");

    // Zoom 模式
    private static final Pattern LINK_PATTERN_ZOOM = Pattern.compile("https://zoom\\.us/j/[^\\s\\n]+");
    private static final Pattern DATE_PATTERN_ZOOM = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})");
    private static final Pattern HOUR_PATTERN_ZOOM = Pattern.compile("(\\d{1,2}:\\d{2})");

    private static final Pattern ROUND_NUMBER_PATTERN = Pattern.compile("[一二三四五六七八九十]|\\d");

    private static final Map<String, Integer> CHINESE_NUMBERS = Map.of(
            "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
            "六", 6, "七", 7, "八", 8, "九", 9, "十", 10
    );

    private static final String PARSE_PROMPT = """
            你是一个专业的面试邀约信息提取助手。请仔细分析以下文本，提取面试相关信息。

            【提取规则】：
            1. companyName（公司名称）：提取面试公司的全称或简称，必需字段；
            2. position（岗位名称）：提取面试岗位的名称，必需字段；
            3. interviewTime（面试时间）：提取面试开始时间并转换为 ISO 8601 格式 (YYYY-MM-DDTHH:MM:SS，如 2026-08-26T14:30:00)，必需字段。若只有相对时间（如"明天下午2点"），根据当前基准日期 %s 推算；
            4. interviewType（面试形式）：ONSITE（现场）/ VIDEO（视频）/ PHONE（电话）；
            5. meetingLink（会议链接）：提取完整的会议链接或会议号+密码；
            6. roundNumber（第几轮面试）：提取整数数字（1-10），如"二面/第2轮"提取为 2；
            7. interviewer（面试官）：若有面试官姓名则提取；
            8. notes（其他备注）：包含时长、技术面/HR面方向等。

            【待解析邀约文本】：
            %s

            【输出格式】：
            请直接输出严格合法的 JSON 对象，不要包含 markdown 代码块包裹，字段样例：
            {"companyName":"字节跳动","position":"Java后端研发工程师","interviewTime":"2026-08-26T15:00:00","interviewType":"VIDEO","meetingLink":"https://meeting.feishu.cn/j/123456","roundNumber":1,"interviewer":"张三","notes":"技术一面"}
            """;

    /**
     * 智能解析面试邀约文本
     */
    public ParseResponse parse(String rawText, String source) {
        log.info("开始解析面试邀约文本: source={}, textLength={}", source, rawText != null ? rawText.length() : 0);

        if (rawText == null || rawText.trim().isEmpty()) {
            return new ParseResponse(false, null, 0.0, "none", "输入文本为空");
        }

        // 1. 优先尝试毫秒级正则规则解析
        CreateInterviewRequest result = tryRuleParsing(rawText, source);
        if (isValidResult(result)) {
            log.info("✅ 正则规则解析成功: company={}, position={}, time={}",
                    result.getCompanyName(), result.getPosition(), result.getInterviewTime());
            return new ParseResponse(true, result, 0.95, "rule", "规则解析成功");
        }

        // 2. 规则解析不完整，回退到 AI 大模型语义抽取
        log.info("ℹ️ 规则解析未命中全部必填字段，转由 AI 大模型深度抽取...");
        result = parseWithAI(rawText);
        if (isValidResult(result)) {
            log.info("✅ AI 大模型解析成功: company={}, position={}, time={}",
                    result.getCompanyName(), result.getPosition(), result.getInterviewTime());
            return new ParseResponse(true, result, 0.88, "ai", "AI 大模型解析成功");
        }

        log.warn("❌ 面试邀约文本解析失败，未提取到有效信息");
        return new ParseResponse(false, null, 0.0, "none", "未能识别有效面试邀约信息，请手动填写");
    }

    private CreateInterviewRequest tryRuleParsing(String rawText, String source) {
        if ("feishu".equalsIgnoreCase(source)) {
            return parseFeishu(rawText);
        } else if ("tencent".equalsIgnoreCase(source)) {
            return parseTencent(rawText);
        } else if ("zoom".equalsIgnoreCase(source)) {
            return parseZoom(rawText);
        }

        if (rawText.contains("飞书") || rawText.contains("Feishu") || rawText.contains("meeting.feishu.cn")) {
            CreateInterviewRequest res = parseFeishu(rawText);
            if (isValidResult(res)) return res;
        }

        if (rawText.contains("腾讯会议") || rawText.contains("Tencent Meeting") || rawText.contains("会议号")) {
            CreateInterviewRequest res = parseTencent(rawText);
            if (isValidResult(res)) return res;
        }

        if (rawText.contains("Zoom") || rawText.contains("zoom.us")) {
            CreateInterviewRequest res = parseZoom(rawText);
            if (isValidResult(res)) return res;
        }

        CreateInterviewRequest res = parseFeishu(rawText);
        if (isValidResult(res)) return res;

        res = parseTencent(rawText);
        if (isValidResult(res)) return res;

        return parseZoom(rawText);
    }

    private CreateInterviewRequest parseFeishu(String rawText) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        try {
            Matcher timeMatcher = TIME_PATTERN_FEISHU.matcher(rawText);
            if (timeMatcher.find()) {
                request.setInterviewTime(parseDateTime(timeMatcher.group(1)));
            }

            Matcher linkMatcher = LINK_PATTERN_FEISHU.matcher(rawText);
            if (linkMatcher.find()) {
                request.setMeetingLink(linkMatcher.group());
            }

            Matcher companyMatcher = COMPANY_PATTERN_FEISHU.matcher(rawText);
            if (companyMatcher.find()) {
                request.setCompanyName(companyMatcher.group(1).trim());
            }

            Matcher positionMatcher = POSITION_PATTERN_FEISHU.matcher(rawText);
            if (positionMatcher.find()) {
                request.setPosition(positionMatcher.group(1).trim());
            }

            Matcher roundMatcher = ROUND_PATTERN_FEISHU.matcher(rawText);
            if (roundMatcher.find()) {
                request.setRoundNumber(parseRoundNumber(roundMatcher.group()));
            }

            request.setInterviewType("VIDEO");
            return request;
        } catch (Exception e) {
            log.debug("飞书正则解析异常: {}", e.getMessage());
            return request;
        }
    }

    private CreateInterviewRequest parseTencent(String rawText) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        try {
            Matcher timeMatcher = TIME_PATTERN_TENCENT.matcher(rawText);
            if (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1) + " " + timeMatcher.group(2);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            Matcher meetingIdMatcher = MEETING_ID_PATTERN_TENCENT.matcher(rawText);
            Matcher passwordMatcher = PASSWORD_PATTERN_TENCENT.matcher(rawText);

            StringBuilder meetingLink = new StringBuilder();
            if (meetingIdMatcher.find()) {
                meetingLink.append("会议号: ").append(meetingIdMatcher.group(1));
            }
            if (passwordMatcher.find()) {
                meetingLink.append(" 密码: ").append(passwordMatcher.group(1));
            }
            if (meetingLink.length() > 0) {
                request.setMeetingLink(meetingLink.toString());
            }

            Matcher companyMatcher = COMPANY_PATTERN_TENCENT.matcher(rawText);
            if (companyMatcher.find()) {
                request.setCompanyName(companyMatcher.group(1).trim());
            }

            Matcher positionMatcher = POSITION_PATTERN_TENCENT.matcher(rawText);
            if (positionMatcher.find()) {
                request.setPosition(positionMatcher.group(1).trim());
            }

            request.setInterviewType("VIDEO");
            return request;
        } catch (Exception e) {
            log.debug("腾讯会议正则解析异常: {}", e.getMessage());
            return request;
        }
    }

    private CreateInterviewRequest parseZoom(String rawText) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        try {
            Matcher linkMatcher = LINK_PATTERN_ZOOM.matcher(rawText);
            if (linkMatcher.find()) {
                request.setMeetingLink(linkMatcher.group());
            }

            Matcher dateMatcher = DATE_PATTERN_ZOOM.matcher(rawText);
            Matcher hourMatcher = HOUR_PATTERN_ZOOM.matcher(rawText);

            if (dateMatcher.find() && hourMatcher.find()) {
                String timeStr = dateMatcher.group(1) + " " + hourMatcher.group(1);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            request.setInterviewType("VIDEO");
            return request;
        } catch (Exception e) {
            log.debug("Zoom 正则解析异常: {}", e.getMessage());
            return request;
        }
    }

    private CreateInterviewRequest parseWithAI(String rawText) {
        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String prompt = String.format(PARSE_PROMPT, currentDate, rawText);

        Map<String, Object> map = aiStructuredClient.callForStructured(
                prompt,
                new TypeReference<Map<String, Object>>() {},
                () -> null
        );

        if (map == null || map.isEmpty()) {
            return null;
        }

        CreateInterviewRequest request = new CreateInterviewRequest();

        if (map.get("companyName") != null) {
            request.setCompanyName(String.valueOf(map.get("companyName")).trim());
        }
        if (map.get("position") != null) {
            request.setPosition(String.valueOf(map.get("position")).trim());
        }
        if (map.get("interviewTime") != null) {
            request.setInterviewTime(parseDateTime(String.valueOf(map.get("interviewTime")).trim()));
        }
        if (map.get("interviewType") != null) {
            request.setInterviewType(String.valueOf(map.get("interviewType")).trim());
        }
        if (map.get("meetingLink") != null) {
            request.setMeetingLink(String.valueOf(map.get("meetingLink")).trim());
        }
        if (map.get("roundNumber") != null) {
            request.setRoundNumber(parseRoundNumber(String.valueOf(map.get("roundNumber"))));
        }
        if (map.get("interviewer") != null) {
            request.setInterviewer(String.valueOf(map.get("interviewer")).trim());
        }
        if (map.get("notes") != null) {
            request.setNotes(String.valueOf(map.get("notes")).trim());
        }

        return request;
    }

    private boolean isValidResult(CreateInterviewRequest request) {
        return request != null
                && request.getCompanyName() != null && !request.getCompanyName().trim().isEmpty()
                && request.getPosition() != null && !request.getPosition().trim().isEmpty()
                && request.getInterviewTime() != null;
    }

    private LocalDateTime parseDateTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
        timeStr = timeStr.trim();
        try {
            if (timeStr.contains("T")) {
                if (timeStr.length() == 16) {
                    return LocalDateTime.parse(timeStr + ":00", DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                }
                return LocalDateTime.parse(timeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            timeStr = timeStr.replace("/", "-");
            if (timeStr.length() == 16) {
                return LocalDateTime.parse(timeStr, DATE_TIME_FORMATTER);
            }
            if (timeStr.length() == 19) {
                return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return LocalDateTime.parse(timeStr, DATE_TIME_FORMATTER);
        } catch (Exception e) {
            log.warn("日期时间解析失败: {}", timeStr);
            return null;
        }
    }

    private int parseRoundNumber(String roundStr) {
        Matcher matcher = ROUND_NUMBER_PATTERN.matcher(roundStr);
        if (matcher.find()) {
            String val = matcher.group();
            if (CHINESE_NUMBERS.containsKey(val)) {
                return CHINESE_NUMBERS.get(val);
            }
            try {
                return Integer.parseInt(val);
            } catch (Exception ignored) {}
        }
        return 1;
    }
}
