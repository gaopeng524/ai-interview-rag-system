package com.example.demo.common.config;

import com.example.demo.common.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration
// 开启方法级鉴权：Controller/Service 上的 @PreAuthorize("hasRole('ADMIN')") 才会生效
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                // 无状态微服务架构：禁用 HttpSession，避免流式响应（SSE）提交后尝试创建 Session 报 WARN
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // SSE 流式与异步派发（async dispatch）：使用 RequestAttribute 存储 SecurityContext，
                // 保证异步派发能恢复认证信息，同时彻底避免向已提交的响应写入 Session Cookie
                .securityContext(ctx -> ctx.securityContextRepository(new RequestAttributeSecurityContextRepository()))

                .authorizeHttpRequests(auth -> auth
                        // ① 静态前端页面公开，否则浏览器打不开 index.html。
                        .requestMatchers("/", "/index.html", "/favicon.svg").permitAll()
                        // ② 只有「注册」(POST /users) 和「登录」公开；
                        //    GET /users、GET /users/{id} 都要 JWT，避免匿名拿到用户列表。
                        //    注意 requestMatchers("/users") 只精确匹配 /users，不会匹配 /users/{id}。
                        .requestMatchers(
                                HttpMethod.POST,
                                "/users",
                                "/users/login"
                        ).permitAll()
                        // ③ 面试方向/技能目录是公开的静态元数据，允许匿名浏览。
                        //    简历（/api/resumes/**）、语音面试（/api/voice-interview/**）涉及用户私有数据，
                        //    必须登录，交给下面的 anyRequest().authenticated() 处理。
                        .requestMatchers(HttpMethod.GET, "/api/interview/skills", "/api/interview/skills/**").permitAll()
                        .anyRequest().authenticated()
                )

                // 未登录/凭证无效时返回 401 JSON（默认是 403，语义是"没权限"而非"没登录"）。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"code\":401,\"message\":\"未登录或登录已过期\"}"
                            );
                        })
                        // 已登录但权限不足（如普通用户调用管理员接口）→ 403 JSON
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"code\":403,\"message\":\"权限不足\"}"
                            );
                        })
                )

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}