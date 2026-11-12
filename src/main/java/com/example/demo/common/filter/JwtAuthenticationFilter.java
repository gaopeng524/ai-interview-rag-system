package com.example.demo.common.filter;

import com.example.demo.common.security.Role;
import com.example.demo.common.security.UserPrincipal;
import com.example.demo.common.security.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            // 从 token 里一次性拿出 userId + username，挂到 principal 上，
            // Controller/Service 直接用，不再逐请求查数据库。
            Claims claims = jwtService.extractClaims(token);
            Long userId = claims.get("userId", Long.class);
            String username = claims.getSubject();

            if (username != null && userId != null &&
                    SecurityContextHolder.getContext().getAuthentication() == null) {

                // 老 token 没有 role claim → 按 USER 处理（最小权限）
                Role role = Role.fromNullable(claims.get(JwtService.CLAIM_ROLE, String.class));
                UserPrincipal principal = new UserPrincipal(userId, username, role);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null,
                                java.util.List.of(new SimpleGrantedAuthority(role.authority())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            // token 无效/过期：不设认证，交给 Security 链返回 401
        }

        filterChain.doFilter(request, response);
    }
}
