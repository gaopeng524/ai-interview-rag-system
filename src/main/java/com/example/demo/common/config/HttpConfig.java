package com.example.demo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 阻塞式 HTTP 客户端配置。
 * <ul>
 *   <li>{@link #restClientBuilder}：供天气 Tool 复用。</li>
 *   <li>流式输出由 Spring AI 的 {@code ChatClient.stream()} 使用 WebClient 完成，
 *       超时配置见 {@link WebClientConfig}。</li>
 * </ul>
 */
@Configuration
public class HttpConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);   // 连接超时 5s
        factory.setReadTimeout(60_000);    // 读取超时 60s
        return RestClient.builder().requestFactory(factory);
    }



}
