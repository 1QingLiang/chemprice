package com.datamarket.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 爬虫行为识别过滤器（2026-09-24 新增）
 *
 * <p>设计原则（很重要，别改）：
 * <ol>
 *   <li><b>先观察、后拦截</b>：默认观察模式（只记日志不拦）。确认无误报后再开拦截。
 *       直接上拦截容易误伤真实用户，而真实用户是业务生命线。</li>
 *   <li><b>只针对「明显异常」特征</b>：脚本 UA、无头浏览器、UA 为空、高频访问。
 *       正常浏览器 UA 一律放行，不受任何行为判定影响。</li>
 *   <li><b>不替代 nginx 限流</b>：速率问题交给 nginx（更高效），这里只做「特征识别」。</li>
 * </ol>
 *
 * <p>⚠️ 实现注意（踩坑记录）：<b>不要重写 initFilterBean()</b>。
 * 曾试过在该方法里起 Timer 做清理，导致过滤器静默不执行（无报错、无日志）。
 * 现改为「访问时顺带惰性清理」，与 OpenApiKeyFilter 保持同一套简单模式。
 */
@Component
@Slf4j
public class BotDetectFilter extends OncePerRequestFilter {

    /** 观察模式：true = 只记录不拦截（默认）；false = 命中特征直接 429 */
    @Value("${security.bot-detect.block:false}")
    private boolean blockMode;

    /** 判定为「高频」的阈值（每分钟），仅用于打标，不直接拦截 */
    @Value("${security.bot-detect.rate-threshold:900}")
    private int rateThreshold;

    /** —— 明确的爬虫 / 脚本特征 UA（小写匹配）—— */
    private static final String[] BOT_UA_MARKERS = {
        "curl/", "wget/", "python-requests", "python-urllib", "httpx/", "aiohttp/",
        "scrapy/", "httpclient", "okhttp/", "go-http-client", "libwww-perl",
        "headlesschrome", "phantomjs", "puppeteer", "playwright", "selenium",
        "postmanruntime", "insomnia", "axios/", "node-fetch", "undici",
        "bytespider", "petalbot", "semrushbot", "ahrefsbot", "mj12bot", "dotbot",
        "gptbot", "ccbot", "claudebot", "anthropic-ai", "perplexitybot",
    };

    /** IP -> [窗口起始毫秒, 计数] */
    private final Map<String, long[]> rateWindow = new ConcurrentHashMap<>();
    /** IP -> 累计可疑次数（用于日志聚合，避免刷屏） */
    private final Map<String, AtomicInteger> suspectCount = new ConcurrentHashMap<>();

    /** 这些路径不参与判定（健康检查、前端资源、公开静态页），否则全是噪音 */
    private static final String[] IGNORE_PREFIX = {
        "/api/health", "/assets/", "/favicon", "/guide/", "/logo", "/cn_geo",
    };

    private volatile long lastGc = System.currentTimeMillis();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String uri = request.getRequestURI();
            if (!isIgnored(uri)) {
                gcIfNeeded();
                String ua = request.getHeader("User-Agent");
                String ip = clientIp(request);
                String reason = detect(ua, ip);
                if (reason != null) {
                    AtomicInteger c = suspectCount.computeIfAbsent(ip, k -> new AtomicInteger());
                    int n = c.incrementAndGet();
                    if (n == 1 || n % 20 == 0) {
                        log.warn("可疑访问 ip={} ua=\"{}\" uri={} 原因={} （该 IP 累计 {} 次）",
                                ip, ua == null ? "(空)" : truncate(ua, 80), uri, reason, n);
                    }
                    if (blockMode) {
                        response.setStatus(429);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write(
                            "{\"code\":429,\"message\":\"访问过于频繁或被识别为自动化程序，请稍后再试\"}");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // ⭐ 识别层绝不能影响主流程：任何异常都放行并记一笔
            log.warn("BotDetectFilter 异常（已放行）：{}", e.toString());
        }
        filterChain.doFilter(request, response);
    }

    /** 返回命中原因；null = 正常放行 */
    private String detect(String ua, String ip) {
        if (ua == null || ua.isBlank()) {
            return "UA 为空";
        }
        String low = ua.toLowerCase();
        for (String m : BOT_UA_MARKERS) {
            if (low.contains(m)) {
                return "脚本/爬虫 UA（" + m + "）";
            }
        }
        if (ip != null && isHighRate(ip)) {
            return "高频访问（>= " + rateThreshold + " 次/分钟）";
        }
        return null;
    }

    /** 简易滑动窗口：同一 IP 60 秒内请求数是否超阈值 */
    private boolean isHighRate(String ip) {
        long now = System.currentTimeMillis();
        long[] w = rateWindow.computeIfAbsent(ip, k -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] >= 60_000L) {
                w[0] = now;
                w[1] = 1;
                return false;
            }
            w[1]++;
            return w[1] >= rateThreshold;
        }
    }

    /** 每 10 分钟顺带清理一次过期条目，防止 Map 无限增长 */
    private void gcIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastGc < 10 * 60_000L) {
            return;
        }
        lastGc = now;
        rateWindow.entrySet().removeIf(e -> now - e.getValue()[0] > 10 * 60_000L);
        suspectCount.entrySet().removeIf(e -> e.getValue().get() == 0);
    }

    private boolean isIgnored(String uri) {
        for (String p : IGNORE_PREFIX) {
            if (uri.startsWith(p)) return true;
        }
        return false;
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
