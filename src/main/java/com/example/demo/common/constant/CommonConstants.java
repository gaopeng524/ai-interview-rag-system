package com.example.demo.common.constant;

/**
 * 通用常量定义（给全项目共享的"魔法数字"起名字）
 *
 * 为什么要集中定义常量？
 * 比如"成功"如果散落在代码里写 200，哪天要改成 0，得全局搜着改；
 * 集中在这里定义一次，改一处全生效。这就是"单一事实来源"（Single Source of Truth）。
 *
 * 设计套路：用 final class + 私有构造器，表示"这是个纯常量工具类，不许被继承、不许被 new"。
 * 内部再嵌套 final class 做分组（StatusCode / Pagination / InterviewDefaults），
 * 相当于给常量建了命名空间，读起来像树状目录。
 */
public final class CommonConstants {

    /** 私有构造器：禁止外部实例化。常量类永远不该有实例。 */
    private CommonConstants() {}

    /**
     * 状态码分组（HTTP 状态码的"项目内称呼"）
     *
     * 为什么不全用 ErrorCode？这里是最常用的几个 HTTP 语义码，而 ErrorCode 是更细的业务错误码。
     * 两层分工：这一层描述"请求结果的大类"，ErrorCode 描述"具体错在哪"。
     */
    public static final class StatusCode {
        public static final int SUCCESS = 200;        // 成功
        public static final int BAD_REQUEST = 400;    // 请求参数错误
        public static final int UNAUTHORIZED = 401;   // 未登录 / 登录失效
        public static final int FORBIDDEN = 403;      // 已登录但没权限
        public static final int NOT_FOUND = 404;      // 资源不存在
        public static final int SERVER_ERROR = 500;   // 服务器内部错误

        /** 分组内部同样私有构造器，防止 new StatusCode()。 */
        private StatusCode() {}
    }

    /**
     * 分页默认值分组（查列表时没传分页参数就用这里的默认值）
     */
    public static final class Pagination {
        public static final int DEFAULT_PAGE = 1;    // 默认第 1 页
        public static final int DEFAULT_SIZE = 20;   // 默认每页 20 条
        public static final int MAX_SIZE = 100;      // 每页最多 100 条（防止有人一次拉 10 万条拖垮 DB）

        private Pagination() {}
    }

    /**
     * 面试默认值分组
     */
    public static final class InterviewDefaults {
        public static final String SKILL_ID = "java-backend";  // 默认面试方向：Java 后端
        public static final String DIFFICULTY = "mid";         // 默认难度：中等

        private InterviewDefaults() {}
    }
}
