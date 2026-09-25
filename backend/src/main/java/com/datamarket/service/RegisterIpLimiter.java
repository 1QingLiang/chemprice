package com.datamarket.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注册 IP 限流：同一 IP 在指定时间窗口内最多允许注册 maxPerWindow 个账号。
 * 内存实现（重启清零），配合注册必填邮箱验证码，足以防批量刷号。
 */
@Component
@Slf4j
public class RegisterIpLimiter {

    /** 每个 IP 在窗口内允许的注册次数 */
    @Value("${auth.register-ip-max:3}")
    private int maxPerWindow;

    /** 窗口时长（小时） */
    @Value("${auth.register-ip-window-hours:24}")
    private int windowHours;

    private final ConcurrentHashMap<String, Deque<Long>> records = new ConcurrentHashMap<>();

    /**
     * 尝试登记一次注册。超过上限返回 false（不记录，保持占用）。
     * 线程安全：对每个 IP 的操作在 synchronized 块内串行化。
     */
    public synchronized boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) return true; // 拿不到 IP 不阻断
        long now = System.currentTimeMillis();
        long windowMs = windowHours * 3600_000L;
        Deque<Long> q = records.computeIfAbsent(ip, k -> new ArrayDeque<>());
        // 清理窗口外的旧记录
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.pollFirst();
        }
        if (q.size() >= maxPerWindow) {
            log.warn("注册被限流：IP {} 在窗口内已达上限 {}", ip, maxPerWindow);
            return false;
        }
        q.addLast(now);
        // 防止 map 无限增长：定期清理过期 key
        if (records.size() > 10_000) {
            prune(now, windowMs);
        }
        return true;
    }

    private void prune(long now, long windowMs) {
        records.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            return q.isEmpty() || now - q.peekLast() > windowMs;
        });
    }

    public int getMaxPerWindow() {
        return maxPerWindow;
    }

    public int getWindowHours() {
        return windowHours;
    }
}
