package com.example.demo.common.config;

import com.example.demo.common.tool.WeatherTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * 把 {@link WeatherTools} 里标了 {@code @Tool} 的方法注册成函数调用工具。
 * ChatResilienceService 把它传给 prompt()，模型就能在对话中自己决定调用 getWeather。
 */
@Configuration
public class ToolConfig {
    @Bean
    public ToolCallbackProvider weatherToolProvider(WeatherTools weatherTools) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherTools).build();
    }
}
