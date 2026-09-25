package com.datamarket.config;

import com.datamarket.security.BotDetectFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 爬虫行为识别过滤器的显式注册（2026-09-24）
 *
 * <p>为什么不用 @Component 自动注册：
 * 本项目已有 {@link CorsFilter} 等以 @Bean 形式声明的 Filter，
 * 多个 Filter Bean 并存时自动注册的顺序与覆盖关系不稳定 ——
 * 实测 BotDetectFilter 加了 @Component 后**完全不执行**（无日志、无报错），
 * 排查多轮无果。改为 FilterRegistrationBean 显式注册后立即生效。
 *
 * <p>注册顺序：排在 Spring Security 过滤器链（默认 order=-100）**之前**，
 * 这样即使未登录也能在鉴权前识别并记录可疑流量。
 */
@Configuration
public class BotDetectConfig {

    @Bean
    public FilterRegistrationBean<BotDetectFilter> botDetectRegistration(BotDetectFilter filter) {
        FilterRegistrationBean<BotDetectFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        // 早于 Spring Security（其 filterChainProxy 默认 order = -100）
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("botDetectFilter");
        return reg;
    }
}
