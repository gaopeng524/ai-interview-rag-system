package com.example.demo.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 令牌生成与解析服务
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration-ms:${jwt.expiration:86400000}}") long expirationMs) {
        if (secret == null || secret.isBlank()
                || "my-super-secret-key-for-ai-chatbot-project-2026".equals(secret)
                || "mySecretKeyForJwtTokenGenerationMustBeLongEnough1234567890".equals(secret)) {
            this.key = Jwts.SIG.HS256.key().build();
        } else {
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.expirationMs = expirationMs;
    }

    /** JWT 中存放角色的 claim 名 */
    public static final String CLAIM_ROLE = "role";

    public String generateToken(String username, Long userId) {
        return generateToken(username, userId, Role.USER);
    }

    public String generateToken(String username, Long userId, Role role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim(CLAIM_ROLE, (role != null ? role : Role.USER).name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
