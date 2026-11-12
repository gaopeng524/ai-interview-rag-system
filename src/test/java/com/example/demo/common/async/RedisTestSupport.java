package com.example.demo.common.async;

import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试用 Redis：
 * <ol>
 *   <li>本机有 Docker → Testcontainers 启动 redis:7-alpine（CI 默认走这条）；</li>
 *   <li>没有 Docker 但设置了环境变量 TEST_REDIS_HOST / TEST_REDIS_PORT → 连接这个现成的 Redis（需 ≥ 6.2，XAUTOCLAIM）；</li>
 *   <li>都没有 → 跳过测试（不是失败）。</li>
 * </ol>
 */
final class RedisTestSupport implements AutoCloseable {

    private final GenericContainer<?> container;
    final LettuceConnectionFactory connectionFactory;

    private RedisTestSupport(GenericContainer<?> container, String host, int port) {
        this.container = container;
        this.connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        this.connectionFactory.afterPropertiesSet();
        this.connectionFactory.start();
    }

    static RedisTestSupport start() {
        String host = System.getenv("TEST_REDIS_HOST");
        if (host != null && !host.isBlank()) {
            int port = Integer.parseInt(System.getenv().getOrDefault("TEST_REDIS_PORT", "6379"));
            return new RedisTestSupport(null, host, port);
        }
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            docker = false;
        }
        Assumptions.assumeTrue(docker, "没有 Docker，也没有设置 TEST_REDIS_HOST，跳过 Redis 集成测试");
        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        return new RedisTestSupport(redis, redis.getHost(), redis.getMappedPort(6379));
    }

    @Override
    public void close() {
        connectionFactory.destroy();
        if (container != null) {
            container.stop();
        }
    }
}
