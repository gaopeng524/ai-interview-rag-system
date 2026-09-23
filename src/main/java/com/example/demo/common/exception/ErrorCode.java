package com.example.demo.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举（全项目唯一的"错误字典"）
 *
 * 核心思想：把"错在哪个环节 + 该显示什么文案"集中定义成一个枚举，
 * 业务代码抛异常时只需说 ErrorCode.XXX，不用自己编数字、不用硬编码文案。
 *
 * 编号分段规划（像电话区号）：
 *   通用 1xxx —— 所有模块共用的错误
 *   简历 2xxx —— resume 模块专属
 *   面试 3xxx —— interview 模块专属
 *   存储 4xxx / 导出 5xxx / 知识库 6xxx / AI 7xxx / 限流 8xxx / 日程 9xxx / 语音 10xxx / Provider 11xxx
 *
 * 好处：
 *   1. 全局唯一：看到 7001 就知道是 AI 服务不可用，不会撞码。
 *   2. 文案复用：一个错误码一个默认文案，前端还能按 code 精确判断（如 401 触发重新登录）。
 *   3. 扩展成本低：新模块错误，往对应段位加一行即可。
 *
 * Lombok 说明：
 *   @Getter           —— 自动生成 getCode() / getMessage()
 *   @AllArgsConstructor —— 自动生成"全参构造器"，配合枚举声明一行行给字段赋值
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ========== 通用错误 1xxx ==========
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // ========== 简历模块错误 2xxx ==========
    RESUME_NOT_FOUND(2001, "简历不存在"),
    RESUME_PARSE_FAILED(2002, "简历解析失败"),
    RESUME_UPLOAD_FAILED(2003, "简历上传失败"),
    RESUME_DUPLICATE(2004, "简历已存在"),
    RESUME_FILE_TYPE_NOT_SUPPORTED(2006, "不支持的文件类型"),
    RESUME_ANALYSIS_FAILED(2007, "简历分析失败"),
    RESUME_ANALYSIS_NOT_FOUND(2008, "简历分析结果不存在"),

    // ========== 面试模块错误 3xxx ==========
    INTERVIEW_SESSION_NOT_FOUND(3001, "面试会话不存在"),
    INTERVIEW_SESSION_EXPIRED(3002, "面试会话已过期"),
    INTERVIEW_QUESTION_NOT_FOUND(3003, "面试问题不存在"),
    INTERVIEW_ALREADY_COMPLETED(3004, "面试已完成"),
    INTERVIEW_EVALUATION_FAILED(3005, "面试评估失败"),
    INTERVIEW_QUESTION_GENERATION_FAILED(3006, "面试问题生成失败"),
    INTERVIEW_NOT_COMPLETED(3007, "面试尚未完成"),
    INTERVIEW_ANSWER_SAVE_FAILED(3008, "面试答案保存失败"),
    INTERVIEW_QUESTION_INSUFFICIENT(3009, "符合条件的面试问题不足"),

    // ========== 存储模块错误 4xxx ==========
    STORAGE_UPLOAD_FAILED(4001, "文件上传失败"),
    STORAGE_DOWNLOAD_FAILED(4002, "文件下载失败"),
    STORAGE_DELETE_FAILED(4003, "文件删除失败"),

    // ========== 导出模块错误 5xxx ==========
    EXPORT_PDF_FAILED(5001, "PDF导出失败"),

    // ========== 知识库模块错误 6xxx ==========
    KNOWLEDGE_BASE_NOT_FOUND(6001, "知识库不存在"),
    KNOWLEDGE_BASE_PARSE_FAILED(6002, "知识库文件解析失败"),
    KNOWLEDGE_BASE_QUERY_FAILED(6004, "知识库查询失败"),
    KNOWLEDGE_BASE_DELETE_FAILED(6005, "知识库删除失败"),
    KNOWLEDGE_BASE_VECTORIZATION_FAILED(6006, "知识库向量化失败"),

    // ========== AI服务错误 7xxx ==========
    AI_SERVICE_UNAVAILABLE(7001, "AI服务暂时不可用，请稍后重试"),
    AI_SERVICE_TIMEOUT(7002, "AI服务响应超时"),
    AI_SERVICE_ERROR(7003, "AI服务调用失败"),
    AI_API_KEY_INVALID(7004, "AI服务密钥无效"),
    AI_RATE_LIMIT_EXCEEDED(7005, "AI服务调用频率超限"),

    // ========== 限流模块错误 8xxx ==========
    RATE_LIMIT_EXCEEDED(8001, "请求过于频繁，请稍后再试"),

    // ========== 面试日程模块错误 9xxx ==========
    INTERVIEW_SCHEDULE_NOT_FOUND(9001, "面试日程不存在"),

    // ========== 语音面试模块错误 10xxx ==========
    VOICE_SESSION_NOT_FOUND(10001, "语音面试会话不存在"),
    VOICE_EVALUATION_FAILED(10004, "语音面试评估失败"),
    VOICE_EVALUATION_NOT_FOUND(10006, "语音面试评估结果不存在"),

    // ========== Provider管理模块错误 11xxx ==========
    PROVIDER_NOT_FOUND(11001, "LLM Provider 不存在"),
    PROVIDER_ALREADY_EXISTS(11002, "LLM Provider 已存在"),
    PROVIDER_CONFIG_READ_FAILED(11004, "读取 Provider 配置失败"),
    PROVIDER_CONFIG_WRITE_FAILED(11005, "写入 Provider 配置失败"),
    PROVIDER_TEST_FAILED(11006, "Provider 连通性测试失败"),
    PROVIDER_DEFAULT_CANNOT_DELETE(11007, "默认 Provider 不可删除"),
    MODULE_NOT_FOUND(11008, "模块不存在"),
    VOICE_CONFIG_READ_FAILED(11009, "读取语音服务配置失败"),
    VOICE_CONFIG_WRITE_FAILED(11010, "写入语音服务配置失败"),
    VOICE_CONFIG_TEST_FAILED(11011, "语音服务连通性测试失败");

    /** 错误编号（全局唯一，前端靠它精确定位错误） */
    private final Integer code;
    /** 默认错误文案（展示给用户看的一句话） */
    private final String message;
}
