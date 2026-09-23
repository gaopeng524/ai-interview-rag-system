# ==============================================================================
# 第一阶段：构建环境 (Maven Builder Stage)
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# 1. 优先复制 Maven 构建配置文件
COPY pom.xml mvnw ./
COPY .mvn .mvn

# 赋予 Wrapper 执行权限并预下载依赖缓存
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

# 2. 复制后端源代码
COPY src src

# 3. 执行打包生成可执行 JAR（跳过单元测试加快镜像构建）
RUN ./mvnw clean package -DskipTests -B

# ==============================================================================
# 第二阶段：生产运行环境 (Runtime Stage)
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 从第一阶段中复制生成的 JAR 文件
COPY --from=builder /workspace/target/*.jar app.jar

# 声明暴露端口
EXPOSE 8080

# 优化容器化 JVM 启动参数
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
