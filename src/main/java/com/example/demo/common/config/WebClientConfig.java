package com.example.demo.common.config;

import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 流式调用使用的 WebClient 配置。
 *
 * <p>Spring AI 的 OpenAI 兼容集成在 {@code stream()} 时走 WebClient/Reactor Netty，
 * 不会使用 {@link HttpConfig} 中的 RestClient。因此连接超时要在这里单独配置。</p>
 */
@Configuration
@Slf4j
public class WebClientConfig {

    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_IDLE_TIMEOUT_SECONDS = 10;

    /**
     * Spring AI 自动注入上下文中的 WebClient.Builder 给 OpenAiApi。
     *
     * <p>连接建立超过 3 秒会失败；连接建立后若连续 10 秒没有收到任何数据也会失败。
     * 后者是“读取空闲超时”，不会因为正常的长文本流式输出在 10 秒后被强制断开。</p>
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .doOnConnected(connection -> connection.addHandlerLast(
                        new ReadTimeoutHandler(READ_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));

        log.info("流式 WebClient 已配置：连接超时 {}ms，读取空闲超时 {}s",
                CONNECT_TIMEOUT_MILLIS, READ_IDLE_TIMEOUT_SECONDS);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
