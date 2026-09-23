package com.example.demo.modules.llmprovider.core;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容端点的路径与客户端构建工具（Spring AI 2.0.0 GA 版本）
 */
public final class ApiPathResolver {

    private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_READ_TIMEOUT = 300000;
    private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

    private ApiPathResolver() {
    }

    public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
        return buildOpenAiClient(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey,
                                                  int connectTimeout, int readTimeout) {
        Timeout timeout = Timeout.builder()
                .connect(Duration.ofMillis(connectTimeout))
                .read(Duration.ofMillis(readTimeout))
                .build();
        ClientOptions options = ClientOptions.builder()
                .apiKey(apiKey)
                .credential(BearerTokenCredential.create(apiKey))
                .baseUrl(resolveVersionedBaseUrl(baseUrl))
                .timeout(timeout)
                .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
                .build();
        return new OpenAIClientImpl(options);
    }

    public static String resolveVersionedBaseUrl(String baseUrl) {
        String stripped = stripTrailingSlashes(baseUrl);
        if (baseUrlContainsVersion(stripped)) {
            return stripped;
        }
        return stripped + "/v1";
    }

    public static boolean baseUrlContainsVersion(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String stripped = stripTrailingSlashes(baseUrl.trim());
        return TRAILING_VERSION.matcher(stripped).find();
    }

    private static String stripTrailingSlashes(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("/+$", "");
    }
}