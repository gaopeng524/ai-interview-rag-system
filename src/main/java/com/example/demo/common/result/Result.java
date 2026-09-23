package com.example.demo.common.result;


import com.example.demo.common.constant.CommonConstants;
import com.example.demo.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 统一响应结果（全项目所有接口的"快递包装箱"）
 *
 * 为什么所有接口都要包一层 Result？
 * 1. 前端解析统一：不管成功失败、不管返回什么数据，永远是 {code, message, data} 三个栏。
 * 2. 禁止直接返回 Entity：后端 Entity 可能带密码等敏感字段，包一层天然阻止裸奔。
 * 3. 错误信息结构化：错误不再是一个 HTTP 状态码，而是"编号 + 文案 + 可带数据"。
 *
 * 不可变设计：三个字段都是 final，没有 setter，只有构造器 + 静态工厂。
 * 好处：对象一旦创建就不会变，多线程安全、不会被别处偷偷改坏。
 * 这是"值对象"（Value Object）的标准写法——数据类尽量不可变。
 */
@Getter   // Lombok：只生成 getter，不生成 setter（配合 final 字段实现不可变）
public class Result<T> {

    /** 业务状态码（0~500 是 HTTP 语义，5000+ 是业务码，见 ErrorCode） */
    private final Integer code;
    /** 人类可读的说明文案，如 "AI服务暂时不可用" */
    private final String message;
    /** 实际数据（泛型：装什么由调用方决定）。失败时通常是 null */
    private final T data;

    /**
     * 私有构造器：不允许外部直接 new Result(...)。
     * 强制所有人走下面的静态工厂方法，保证对象一定是从规范入口创建的。
     */
    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ========== 成功响应 ==========

    /** 成功，不携带数据（如"删除成功"） */
    public static <T> Result<T> success() {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", null);
    }

    /** 成功，携带数据（最常用，如查出来的列表、创建的对象） */
    public static <T> Result<T> success(T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", data);
    }

    /** 成功，自定义成功文案 + 数据（较少用） */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, message, data);
    }

    // ========== 失败响应 ==========

    /** 失败：不指定错误码，默认给服务器错误 500（兜底场景用） */
    public static <T> Result<T> error(String message) {
        return new Result<>(CommonConstants.StatusCode.SERVER_ERROR, message, null);
    }

    /** 失败：手动指定错误码 + 文案（最灵活） */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败：直接传一个 ErrorCode 枚举，自动带出编号和默认文案 */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败：传 ErrorCode 但覆盖默认文案（想给用户更具体的提示时用） */
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    // ========== 辅助方法 ==========

    /** 判断这次响应是不是成功。前端/后端统一用这个，不要到处写 code == 200 */
    public boolean isSuccess() {
        return CommonConstants.StatusCode.SUCCESS == this.code;
    }
}
