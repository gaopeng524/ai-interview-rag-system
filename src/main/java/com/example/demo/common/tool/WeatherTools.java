package com.example.demo.common.tool;

import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 天气查询工具（函数调用演示），真实数据来自 wttr.in（免 key 免费天气服务）。
 *
 * <p>链路：模型判断需要查天气 → 决定调用 getWeather 并给出参数 →
 * Spring AI 自动执行这个方法 → 这里真实请求 wttr.in → 把结果回填给模型 → 模型组织最终回答。
 *
 * <p>wttr.in 是海外服务，国内访问偶发慢/不稳定，调用失败时返回兜底文案，
 * 不让整个对话挂掉。真实生产可换和风天气等国内源。
 */
@Component
public class WeatherTools {

    /** wttr.in 的 weatherCode → 中文描述（常见代码；未命中退回 wttr.in 给的英文描述） */
    private static final Map<String, String> WEATHER_TEXT = Map.ofEntries(
            Map.entry("113", "晴"),
            Map.entry("116", "大部晴朗"),
            Map.entry("119", "多云"),
            Map.entry("122", "阴天"),
            Map.entry("143", "雾"),
            Map.entry("176", "零星小雨"),
            Map.entry("200", "雷阵雨"),
            Map.entry("227", "冻雾"),
            Map.entry("263", "毛毛雨"),
            Map.entry("266", "小雨"),
            Map.entry("293", "小阵雨"),
            Map.entry("296", "小雨"),
            Map.entry("299", "中雨"),
            Map.entry("302", "中雨"),
            Map.entry("305", "大雨"),
            Map.entry("308", "大雨"),
            Map.entry("311", "冻雨"),
            Map.entry("320", "雨夹雪"),
            Map.entry("323", "小雪"),
            Map.entry("329", "中雪"),
            Map.entry("338", "大雪"),
            Map.entry("353", "小雨"),
            Map.entry("356", "中雨"),
            Map.entry("359", "大雨"),
            Map.entry("386", "雷阵雨"),
            Map.entry("395", "暴雪")
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WeatherTools(RestClient.Builder restClientBuilder) {
        // 复用 ResilienceConfig 里的 RestClient.Builder（自带连接 5s / 读取 60s 超时）
        this.restClient = restClientBuilder.baseUrl("https://wttr.in").build();
    }

    @Tool(description = "查询指定城市的当前天气情况")
    public String getWeather(@ToolParam(description = "城市名，例如：北京、上海，或拼音 Beijing") String city) {
        try {
            // 一次请求拿完整 JSON：format=j1（完整天气数据）+ lang=zh（天气现象返回中文）
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/{city}")
                            .queryParam("lang", "zh")
                            .queryParam("format", "j1")
                            .build(city))
                    .retrieve()
                    .body(String.class);

            JsonNode current = objectMapper.readTree(body).path("current_condition").path(0);
            String code = current.path("weatherCode").asText();
            String desc = WEATHER_TEXT.getOrDefault(code,
                    current.path("weatherDesc").path(0).path("value").asText());
            String temp = current.path("temp_C").asText();
            String wind = current.path("windspeedKmph").asText()
                    + "km/h（" + current.path("winddir16Point").asText() + "风）";
            String humidity = current.path("humidity").asText();

            return city + "当前天气：" + desc + "，" + temp + "°C，风速 " + wind + "，湿度 " + humidity + "%";
        } catch (Exception e) {
            // 海外服务不稳，失败给兜底文案，让模型基于它组织回答，而不是抛异常打断
            return city + "天气暂时查询不到（服务不稳定），请稍后再试。";
        }
    }
}
