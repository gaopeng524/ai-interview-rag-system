package com.example.demo.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 分布式锁与看门狗核心配置
 */
@Configuration
public class RedissonConfig {

    @Bean
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {

        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        var singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setTimeout(3000)
                .setConnectTimeout(5000);

        if (password != null && !password.isBlank()) {
            singleServerConfig.setPassword(password);
        }

        return Redisson.create(config);
    }
}