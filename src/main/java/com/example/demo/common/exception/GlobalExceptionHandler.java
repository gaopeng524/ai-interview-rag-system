package com.example.demo.common.exception;


import com.example.demo.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.SocketTimeoutException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器（全项目所有异常的"最后一站"）
 *
 * 核心机制：
 *   @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 *   它像一道"异常安全网"：任何 Controller/Service 抛出的异常，都会先来这里找匹配的
 *   @ExceptionHandler 方法处理。业务代码只管 throw，永远不用 try-catch 去拼错误响应。
 *
 * 关键设计（融合方案）：
 *   既给真实的 HTTP 状态码，又给统一的 Result body（{code, message, data}）。
 *   参考项目 interview-guide 是"全 200"（所有错误都回 HTTP 200，全靠 body.code 区分），
 *   但那个方案必须前端配合（前端不能用 resp.status === 401 判断登录过期，要改读 body）。
 *   我们项目前端暂时不改，所以取"真实状态码 + Result body"：
 *     1. HTTP 状态码表达"这个请求本身合不合法"（401 未登录 / 403 无权限 / 404 不存在 / 500 服务端炸）
 *     2. body.code 表达"具体业务错误码"（2001 简历不存在、7001 AI 服务不可用…更精确）
 *   前端判断顺序：先看 HTTP 状态码（401 → 重新登录），再读 body.code 做业务提示。
 *   注意：业务错误码（如 7001）没有对应的 HTTP 状态，这类"请求合法但业务失败"仍回 200。
 *
 * @ExceptionHandler 匹配规则：优先匹配"最具体"的异常类型。
 *   所以 Exception.class 这个兜底处理器永远不会抢走上面具体处理器的活。
 *   （比如抛 BusinessException 时，会选中 handleBusinessException 而不是 handleException）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 手动建 Logger：这里没加 @Slf4j，展示"纯手动"的写法。日志对象是全局唯一、线程安全的 */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 错误码 → HTTP 状态码 的映射规则（融合方案的核心）。
     *   - 通用错误码（400/401/403/404/405/500 等）本身就是标准 HTTP 状态码 → 直接透传
     *   - 业务错误码（2001 简历、7001 AI…）没有对应 HTTP 状态 → 回 200，靠 body.code 区分
     * 这样 HTTP 层表达"请求本身对不对"，业务层由 code 精确表达，两者各司其职。
     */
    private HttpStatus resolveStatus(Integer code) {
        if (code != null && code >= 400 && code < 600) {
            try {
                return HttpStatus.valueOf(code);
            } catch (IllegalArgumentException ignored) {
                // 数字在 400~599 但不在 HttpStatus 枚举里（如 999），落到 200
            }
        }
        return HttpStatus.OK;
    }

    /**
     * 处理业务异常（最常触发）
     * 业务代码抛的任何 BusinessException 都到这儿，返回它自带的 code + message。
     * 用 WARN 级别：业务失败是"预期内的失败"（用户输入导致的），不是程序 bug，不该刷 ERROR。
     * HTTP 状态码由 code 决定：401/403/404 这类通用码直接透传，业务码回 200。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(resolveStatus(e.getCode()))
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 处理参数校验异常（@Valid 校验失败时抛）
     * 场景：请求体用了 @Valid + @NotBlank 等注解，某个字段没填/格式错。
     * 把 bindingResult 里所有字段错误拼接成一句，比如 "用户名不能为空, 密码长度至少6位"。
     * 参数有问题 → 400 Bad Request。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 处理绑定异常（@RequestBody 反序列化/字段类型转换失败时抛）
     * 与上面那个的区别：MethodArgumentNotValidException 是"校验注解不过"，
     * BindException 是"类型绑不上"（比如把 "abc" 绑到 Integer 字段上）。
     * 两者都算参数问题，统一回 BAD_REQUEST(400)。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 处理文件上传大小超限异常
     * 场景：spring.servlet.multipart.max-file-size 设了上限，用户传了大文件。
     * 单独处理：否则会被兜底 500，用户看到的就不是友好的"文件太大"而是"系统繁忙"。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("文件上传大小超限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, "文件大小超过限制"));
    }

    /**
     * 处理非法参数异常
     * 场景：业务代码自己检测到非法输入，用 throw new IllegalArgumentException("...") 主动拦截。
     * 这也是预期内的参数问题，给 BAD_REQUEST(400) 而不是 500。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    /**
     * 处理 AI 服务网络异常（连接层失败）
     * 场景：调大模型时 SSL 握手失败、连接超时——RestClient/WebClient 底层抛 ResourceAccessException。
     *
     * 这段是"错误细分"的示范：同样是 AI 连接失败，根据根因(cause)给不同错误码：
     *   - SocketTimeoutException（超时）→ 7002 超时
     *   - 消息含 "handshake"（握手失败，多半网络不稳）→ 7001 连接失败
     *   - 其他 → 7001 服务不可用
     * HTTP 状态：AI 是"上游依赖"，它连不上属于网关错误 → 502 Bad Gateway。
     *
     * 语法说明：参考项目用 switch 模式匹配（case SocketTimeoutException _），
     * 那需要 Java 21 的类型模式 + Java 22 的未命名变量 _。我们这里用 if + instanceof
     * 等价改写，JDK 17~26 全兼容。你现在是 JDK 26，如果想体验新语法，可以把这段
     * 换成参考项目的 switch 版对照着看（逻辑完全等价）。
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Result<Void>> handleResourceAccessException(ResourceAccessException e) {
        log.error("AI服务连接失败: {}", e.getMessage(), e);

        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(ErrorCode.AI_SERVICE_TIMEOUT, "AI服务响应超时，请稍后重试"));
        }
        String message = e.getMessage();
        if (message != null && message.contains("handshake")) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI服务连接失败（网络不稳定），请检查网络或稍后重试"));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI服务暂时不可用，请稍后重试"));
    }

    /**
     * 处理 AI 服务调用异常（HTTP 响应层失败）
     * 场景：模型 API 返回了错误状态码（401 密钥错、429 限频、5xx 服务端错）。
     * 根据错误消息内容细分：
     *   - 含 "401"/"Unauthorized" → 7004 密钥无效（让用户知道是 key 的问题）
     *   - 含 "429"/"Too Many Requests" → 7005 限频
     *   - 其他 → 7003 通用调用失败
     * 这样用户看到的不再是笼统的"调用失败"，而是能定位问题的一句话。
     * HTTP 状态：同样是上游(AI)出错 → 502。
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Result<Void>> handleRestClientException(RestClientException e) {
        log.error("AI服务调用失败: {}", e.getMessage(), e);

        String message = e.getMessage();
        if (message == null) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试"));
        }
        if (message.contains("401") || message.contains("Unauthorized")) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(ErrorCode.AI_API_KEY_INVALID, "AI服务密钥无效，请联系管理员"));
        }
        if (message.contains("429") || message.contains("Too Many Requests")) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "AI服务调用过于频繁，请稍后重试"));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.error(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试"));
    }

    /**
     * 处理 404 - 资源未找到异常
     * 场景：前端请求了不存在的 API 路径（或静态资源不存在）。
     * 单独建处理器：否则浏览器自动请求 /favicon.ico 这种"资源不存在"也会被当 500 刷 ERROR。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFoundException(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("资源未找到: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(ErrorCode.NOT_FOUND, "API 接口不存在"));
    }

    /**
     * 处理请求方法不支持异常
     * 场景：对 GET 接口发了 POST 等。HTTP 层直接抛，这里翻译成友好提示，状态码 405。
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleHttpRequestMethodNotSupportedException(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {} {}", e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.error(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod()));
    }

    /**
     * 处理客户端提前断开（SSE 流中途关闭、浏览器切走等）：正常现象，不是服务端错误。
     * 返回 void 不再尝试写响应——否则往已断开的连接里塞 JSON 会再抛一次异常，
     * 把"正常断连"搞成 ERROR 刷日志。
     * ⚠️ 这个处理器是参考项目没有、我们项目必须留的（我们踩过这个坑）：
     *   丢了这个处理器，客户端断开时会被下面的 Exception 兜底接管，
     *   往断开的响应里写 JSON → 二次异常刷日志（就是我们之前修的 SSE 网络错误）。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e) {
        log.info("客户端断开连接（流式请求提前关闭）：{}", e.getMessage());
    }

    /**
     * 处理 Tomcat 的客户端断开异常（静态资源请求中途断开等）。
     * 与 AsyncRequestNotUsableException 类似，返回 void 避免二次写入。
     */
    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbort(org.apache.catalina.connector.ClientAbortException e) {
        log.info("客户端中断连接：{}", e.getMessage());
    }

    /**
     * 处理其他未知异常（兜底）
     * 所有没被上面具体处理器接住的异常都到这儿，打 ERROR 带完整堆栈。
     * HTTP 状态码 500：没预料到的异常 = 服务端内部错误。
     * 堆栈详情是给开发者的，绝不能暴露给前端（信息泄露风险），响应只给一句"系统繁忙"。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试"));
    }
}
