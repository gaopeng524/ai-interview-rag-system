package com.example.demo;

import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


/**
 * 应用入口。
 *
 * <p>@ConfigurationPropertiesScan：自动扫描并注册所有 @ConfigurationProperties 类
 * （如 ai 包下的 LlmProviderProperties）。没有它，Spring 不会把 app.ai.* 配置绑定成 bean，
 * LlmProviderRegistry 注入时会直接报"NoSuchBeanDefinitionException"。
 *
 * <p>@SpringBootApplication(exclude = ...)：关闭 Spring AI starter 的"模型自动配置"。
 * 阶段②起，ChatClient 的创建权收归 LlmProviderRegistry（见其类注释），
 * 这些自动配置会按 spring.ai.openai.* 抢着建没人用的模型 bean。
 * 这里一次性全排除（含 ChatClientAutoConfiguration，它没有 ChatModel bean 会找不到依赖），
 * 让注册表成为 ChatClient 的唯一来源。以后要加新供应商，配置 + 注册表即可，这里不用动。
 *
 * <p>统一使用 OpenAI 兼容协议：所有供应商（包括 DeepSeek）都走 OpenAI 兼容模式，
 * 代码更简洁，扩展性更强。
 */
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = {
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class,
        ChatClientAutoConfiguration.class
})
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

}
