package com.datamarket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.datamarket.security.JwtAuthFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .headers(h -> h.frameOptions(f -> f.disable()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/reset-password", "/api/auth/send-code", "/api/auth/verify-code", "/api/auth/reset-password-by-email", "/api/health").permitAll()
                // AI 导出下载：浏览器点链接不带 JWT，改由 ExportService 的签名令牌（30 分钟）鉴权
                .requestMatchers("/api/ai/export").permitAll()
                // 公众号服务器回调：微信服务器不带 JWT，靠 Token 的 SHA1 签名自校验
                .requestMatchers("/api/wechat/mp", "/api/wechat/mp/**").permitAll()
                // 开放数据 API：JWT 体系之外，由 OpenApiKeyFilter 用请求头 X-API-Key 鉴权（401/429）
                .requestMatchers("/api/open/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/favorites/**").authenticated()
                .requestMatchers("/index.html", "/", "/*.js", "/*.css", "/*.ico", "/assets/**").permitAll()
                .requestMatchers("/login", "/dashboard", "/price-table", "/commodities", "/commodity/**", "/movers", "/user-manage", "/audit-log", "/ai-logs").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            // ⭐ 未认证一律返回 401（而不是 Spring Security 默认的 403）。
            // 原因：前端 api/index.js 的响应拦截器只把 401 当作"登录已过期"→ clearAuth + 跳登录页；
            // 若这里回 403，用户 token 过期后页面不会跳登录，而是所有接口静默失败（2026-09-20 真实故障）。
            // 权限不足（已登录但无角色）仍返回 403，前端不会误登出。
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":401,\"message\":\"登录状态已失效，请重新登录\"}");
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(403);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":403,\"message\":\"没有权限执行此操作\"}");
                })
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
