# 02 · 安全与 JWT

覆盖：JWT 原理、`JwtService`、`SecurityConfig`、`JwtAuthenticationFilter`、`UserPrincipal`。核心问题是「请求是怎么被认证的，Controller 怎么知道当前是谁」。

## JWT 是什么（三段式）

JWT（JSON Web Token）是一串字符串，分三部分，用 `.` 连接：

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSIsInVzZXJJZCI6Mn0.ycClBCf8JSvYX0zBg...
└──────头部──────┘└─────────载荷────────┘└──────────签名──────────┘
```

1. **Header**：签名算法（HS256）
2. **Payload**：业务数据，明文（Base64），我们塞了 `sub=用户名`、`userId=2`、`iat=签发时间`、`exp=过期时间`
3. **Signature**：用密钥对整个内容算 HMAC 签名

关键点：**Payload 只是 Base64，不是加密**，谁都能解码看。真正防篡改的是签名——只要内容被改过，签名就对不上，`JwtService.extractClaims` 就抛异常。所以 token 里别放密码等敏感信息，只放 userId。

类比：**JWT 像一张盖了钢印的门禁卡**。卡上写着你是几号房的（明文），但钢印（签名）只有保安（服务器密钥）才能盖。想伪造"我是 1 号房"，你没有钢印工具，做不出来。

## JwtService —— 签发与解析

```java
public String generateToken(String username, Long userId) {
    Date now = new Date();
    Date expireTime = new Date(now.getTime() + expiration);
    return Jwts.builder()
            .subject(username)
            .claim("userId", userId)      // 关键：把 userId 塞进 token
            .issuedAt(now)
            .expiration(expireTime)
            .signWith(secretKey)
            .compact();
}

public Claims extractClaims(String token) {
    return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
}
```

- `@Value("${jwt.secret}")` 把 yml 里的密钥注入构造器，`Keys.hmacShaKeyFor()` 转成 `SecretKey`。
- **为什么 token 里带 userId**：后续每个请求只需解析 token 就能知道当前用户，**不用再查一次数据库**。这就是无状态认证的核心。

## SecurityConfig —— 安全过滤链（逐行拆）

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())                                   // ①
        .securityContext(ctx -> ctx.requireExplicitSave(false))        // ②
        .authorizeHttpRequests(auth -> auth                            // ③
            .requestMatchers("/", "/index.html", "/favicon.svg").permitAll()
            .requestMatchers(HttpMethod.POST, "/users", "/users/login").permitAll()
            .anyRequest().authenticated()
        )
        .exceptionHandling(ex -> ex                                    // ④
            .authenticationEntryPoint(...)                             // 401 JSON
        )
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class); // ⑤
    return http.build();
}
```

| 行   | 做什么                          | 为什么                                                                                                      |
| --- | ---------------------------- | -------------------------------------------------------------------------------------------------------- |
| ①   | 关 CSRF                       | 我们是纯 API + JWT 无状态，没有会话 Cookie 可被跨站利用                                                                    |
| ②   | `requireExplicitSave(false)` | **SSE 流式的大坑**，见 [[12-坑与排查]]                                                                              |
| ③   | 放行白名单 + 其余全要认证               | 只放行：静态页、注册、登录。**`requestMatchers("/users")` 是精确匹配**，`/users/{id}` 不会被它放行（这是当初 `GET /users/{id}` 401 的原因） |
| ④   | 401 返回 JSON                  | 默认未登录返回 403（语义是"没权限"），改成 401（"没登录"）+ 统一 JSON 格式                                                          |
| ⑤   | 插入自定义过滤器                     | 在用户名密码过滤器之前，让 JWT 先于任何认证逻辑生效                                                                             |

## JwtAuthenticationFilter —— 每个请求的"验卡处"

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    protected void doFilterInternal(HttpServletRequest request, ...) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);   // 没带卡：放行，交给后面判 401
            return;
        }
        String token = authHeader.substring(7);
        try {
            Claims claims = jwtService.extractClaims(token);
            Long userId = claims.get("userId", Long.class);
            String username = claims.getSubject();
            if (username != null && userId != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserPrincipal principal = new UserPrincipal(userId, username);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, null);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            // token 无效/过期：不设认证，交给 Security 链返回 401
        }
        filterChain.doFilter(request, response);
    }
}
```

三个知识点：

1. **`OncePerRequestFilter`**：保证一次请求只执行一次（重定向/转发不算），避免重复验卡。
2. **`SecurityContextHolder`** 是 Spring Security 的"当前请求是谁"的容器，线程本地变量。认证信息塞进去，后续任何地方都能读。
3. **token 无效直接吞异常**：不是让它崩溃，而是保持"未认证"状态，让 Security 链最后统一返回 401。

## UserPrincipal —— 认证后的"身份卡"

```java
public record UserPrincipal(Long id, String username) {}
```

一个 **record**（Java 16+）：自动生成构造器、getter、equals、toString。它不查库，纯粹是 JWT 解析结果的载体。

**Controller 怎么拿当前用户？**
```java
public List<ChatSession> list(@AuthenticationPrincipal UserPrincipal principal) {
    return chatSessionService.findByUserId(principal.id());
}
```

`@AuthenticationPrincipal` 注解直接从 `SecurityContextHolder` 里取 principal 注入。这就是为什么 Service 层校验"这个会话是不是你的"不需要再查库——`principal.id()` 就是当前登录用户的 id。

## 认证流程时序

```
请求带 Authorization: Bearer <token>
  │
  ▼ JwtAuthenticationFilter
  ├─ 没带卡 → 放行 → AuthorizationFilter 判 401
  └─ 带卡 → extractClaims 验签名/过期
       ├─ 成功 → 塞 SecurityContext → 后续 @AuthenticationPrincipal 拿到身份
       └─ 失败 → 吞异常 → 仍匿名 → 401
```

> 相关：[[03-Controller层]] · [[07-数据访问层]] · [[12-坑与排查]]
