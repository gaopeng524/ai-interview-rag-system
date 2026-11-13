package com.example.demo.modules.llmprovider.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.security.JwtService;
import com.example.demo.common.security.Role;
import com.example.demo.modules.llmprovider.core.LlmProviderProperties;
import com.example.demo.modules.llmprovider.core.LlmProviderRegistry;
import com.example.demo.modules.llmprovider.mapper.LlmProviderMapper;
import com.example.demo.modules.llmprovider.model.dto.LlmProviderDTO;
import com.example.demo.modules.llmprovider.model.entity.LlmProviderEntity;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("LLM SSRF 白名单与 JWT 密钥加固测试")
class LlmProviderSecurityTest {

    @Test
    @DisplayName("LlmProviderProperties 仅允许已配置或白名单 HTTPS 主机并拒绝伪造 URL")
    void providerEndpointAllowsTrustedHostsAndRejectsSsrfUrls() {
        var cfg = new LlmProviderProperties.ProviderConfig();
        cfg.setBaseUrl("https://api.deepseek.com/v1");

        var properties = new LlmProviderProperties();
        properties.setProviders(Map.of("deepseek", cfg));
        properties.setAdditionalAllowedHosts(Set.of("example.com", "example.org"));

        assertEquals("api.deepseek.com", properties.requireAllowedEndpoint("https://api.deepseek.com/v1").getHost());
        assertEquals("example.com", properties.requireAllowedEndpoint("https://example.com/v1").getHost());
        assertEquals("example.com", properties.requireAllowedEndpoint("https://example.com:443/v1").getHost());

        assertThrows(BusinessException.class, () -> properties.requireAllowedEndpoint("http://example.com/v1"));
        assertThrows(BusinessException.class, () -> properties.requireAllowedEndpoint("https://example.com@127.0.0.1/v1"));
        assertThrows(BusinessException.class, () -> properties.requireAllowedEndpoint("https://example.com:8443/v1"));
        assertThrows(BusinessException.class, () -> properties.requireAllowedEndpoint("https://example.com/v1?x=1"));
        assertThrows(BusinessException.class, () -> properties.requireAllowedEndpoint("https://unknown.example/v1"));
    }

    @Test
    @DisplayName("更换模型主机时禁止沿用原主机的 API Key，新建供应商也必须校验 URL")
    void createAndUpdateEnforceTrustedHostAndKeyRotation() {
        var properties = new LlmProviderProperties();
        properties.setAdditionalAllowedHosts(Set.of("example.com", "example.org"));

        var mapper = mock(LlmProviderMapper.class);
        var registry = mock(LlmProviderRegistry.class);
        var service = new LlmProviderConfigService(mapper, registry, properties);

        assertThrows(BusinessException.class, () -> service.createProvider(new LlmProviderDTO.CreateRequest(
                "custom", "http://127.0.0.1:8080/v1", "k", "m", null, null, false, 0.2)));
        verify(mapper, never()).insert(any());

        var provider = new LlmProviderEntity();
        provider.setId("provider");
        provider.setBaseUrl("https://example.com/v1");
        provider.setApiKey("one, two");
        when(mapper.findById("provider")).thenReturn(provider);

        assertThrows(BusinessException.class, () -> service.updateProvider("provider",
                new LlmProviderDTO.UpdateRequest("https://example.org/v1", null, null, null, null, null, null)));
        assertThrows(BusinessException.class, () -> service.updateProvider("provider",
                new LlmProviderDTO.UpdateRequest("https://example.org/v1", "three, two", null, null, null, null, null)));
        verify(mapper, never()).update(any());

        service.updateProvider("provider",
                new LlmProviderDTO.UpdateRequest("https://example.org/v1", "three", null, null, null, null, null));
        verify(mapper).update(provider);
        verify(registry).reload();
    }

    @Test
    @DisplayName("未配置或使用示例 JWT 密钥时生成独立进程临时密钥，已配置长密钥支持跨实例验签")
    void jwtUsesEphemeralKeyWhenUnsetOrPlaceholderAndDeterministicWhenConfigured() {
        JwtService blankA = new JwtService("", 60_000L);
        JwtService blankB = new JwtService("", 60_000L);
        String tokenA = blankA.generateToken("alice", 1L, Role.ADMIN);
        assertEquals("ADMIN", blankA.extractClaims(tokenA).get(JwtService.CLAIM_ROLE, String.class));
        assertThrows(JwtException.class, () -> blankB.extractClaims(tokenA));

        JwtService legacyA = new JwtService("my-super-secret-key-for-ai-chatbot-project-2026", 60_000L);
        JwtService legacyB = new JwtService("mySecretKeyForJwtTokenGenerationMustBeLongEnough1234567890", 60_000L);
        assertThrows(JwtException.class, () -> legacyB.extractClaims(legacyA.generateToken("bob", 2L)));

        String strongSecret = "configured-production-secret-at-least-32-bytes";
        JwtService prodA = new JwtService(strongSecret, 60_000L);
        JwtService prodB = new JwtService(strongSecret, 60_000L);
        String prodToken = prodA.generateToken("carol", 3L, Role.USER);
        assertEquals(3L, prodB.extractClaims(prodToken).get("userId", Long.class));

        assertThrows(WeakKeyException.class, () -> new JwtService("too-short", 60_000L));
    }
}
