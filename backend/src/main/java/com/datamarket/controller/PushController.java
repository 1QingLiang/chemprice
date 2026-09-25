package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.InputStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 关注产品邮件推送：用户侧配置（开关 / 发送时间档位 / 勾选要推送的关注产品，额度内）
 */
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    @Value("${push.test-interval-seconds:60}")
    private long testIntervalSeconds;

    /** 允许的发送时间档位 */
    public static final Set<String> ALLOWED_TIMES = Set.of(
        "08:30", "10:30", "11:00", "12:00", "14:00",
        "16:00", "17:00", "17:30", "18:00", "19:00", "20:00");

    private static final int MAX_QUOTA = 50;

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return jdbcTemplate.queryForObject(
            "SELECT id FROM sys_user WHERE username = ?", Long.class, auth.getName());
    }

    /** 当前用户角色（ADMIN 推送额度不受限） */
    private boolean isAdminUser(Long userId) {
        try {
            String role = jdbcTemplate.queryForObject(
                "SELECT role FROM sys_user WHERE id = ?", String.class, userId);
            return "ADMIN".equals(role);
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取用户推送配置 + 其关注列表（含每个关注项是否已勾选推送） */
    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig() {
        Long userId = getUserId();
        ensureConfigRow(userId);

        Map<String, Object> cfg = jdbcTemplate.queryForMap(
            "SELECT enabled, push_time AS pushTime, push_quota AS pushQuota FROM push_config WHERE user_id = ?", userId);
        Map<String, Object> result = new LinkedHashMap<>(cfg);

        List<Map<String, Object>> favs = jdbcTemplate.queryForList(
            "SELECT f.varieties_id AS varietiesId, c.name AS varietiesName, " +
            "f.market_name AS marketName, f.table_type AS tableType, f.push_enabled AS pushEnabled " +
            "FROM user_favorite f JOIN commodity c ON c.varieties_id = f.varieties_id " +
            "WHERE f.user_id = ? ORDER BY f.created_at DESC", userId);
        int used = 0;
        for (Map<String, Object> f : favs) {
            Object pe = f.get("pushEnabled");
            if (pe != null && ((Number) pe).intValue() == 1) used++;
        }
        result.put("favorites", favs);
        result.put("used", used);
        result.put("unlimited", isAdminUser(userId)); // 管理员推送额度不受限
        return Result.ok(result);
    }

    /**
     * 保存推送配置。
     * body: { enabled, pushTime, items: [ {varietiesId, marketName, tableType, pushEnabled} ... ] }
     * items 中 pushEnabled=true 的数量不能超过 push_quota。
     */
    @PostMapping("/config")
    @Transactional
    public Result<String> saveConfig(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        ensureConfigRow(userId);

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String pushTime = body.get("pushTime") == null ? "17:30" : String.valueOf(body.get("pushTime"));
        if (!ALLOWED_TIMES.contains(pushTime)) {
            return Result.error("发送时间须在允许的档位内");
        }

        // 解析要推送的条目
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("items", List.of());
        int wantEnable = 0;
        for (Map<String, Object> it : items) {
            if (Boolean.TRUE.equals(it.get("pushEnabled"))) wantEnable++;
        }

        Integer quota = jdbcTemplate.queryForObject(
            "SELECT push_quota FROM push_config WHERE user_id = ?", Integer.class, userId);
        int q = quota == null ? 3 : quota;

        // 管理员推送额度不受限；普通用户仍按额度校验
        boolean unlimited = isAdminUser(userId);
        if (enabled && !unlimited && wantEnable > q) {
            return Result.error("推送产品数超过额度（上限" + q + "个），如需更多请联系管理员开通");
        }

        // 1. 保存开关与时间
        jdbcTemplate.update(
            "UPDATE push_config SET enabled = ?, push_time = ?, updated_at = NOW() WHERE user_id = ?",
            enabled ? 1 : 0, pushTime, userId);

        // 2. 同步关注项的推送标记：先全部关掉，再按勾选开启
        jdbcTemplate.update("UPDATE user_favorite SET push_enabled = 0 WHERE user_id = ?", userId);
        Set<String> enableKeys = new HashSet<>();
        for (Map<String, Object> it : items) {
            if (Boolean.TRUE.equals(it.get("pushEnabled"))) {
                enableKeys.add(it.get("varietiesId") + "|" + String.valueOf(it.get("marketName")) + "|" + it.get("tableType"));
            }
        }
        for (String key : enableKeys) {
            String[] p = key.split("\\|", 3);
            jdbcTemplate.update(
                "UPDATE user_favorite SET push_enabled = 1 WHERE user_id = ? AND varieties_id = ? AND market_name = ? AND table_type = ?",
                userId, Integer.parseInt(p[0]), p[1], p[2]);
        }
        auditService.ok("PUSH_CONFIG_UPDATE", "BUSINESS", "PUSH_CONFIG", String.valueOf(userId),
            null, null, "更新推送设置（" + (enabled ? "开启每日推送" : "关闭每日推送") + "，发送时间 "
                + pushTime + "，勾选推送 " + wantEnable + " 条关注产品）");
        return Result.ok("保存成功");
    }

    /** 懒初始化配置行（默认：关、17:30、额度3） */
    private void ensureConfigRow(Long userId) {
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM push_config WHERE user_id = ?", Integer.class, userId);
        if (exists == null || exists == 0) {
            jdbcTemplate.update(
                "INSERT INTO push_config (user_id, enabled, push_time, push_quota, updated_at) VALUES (?, 0, '17:30', 3, NOW())",
                userId);
        }
    }

    /**
     * 测试推送：立即向当前用户注册邮箱发送一封"当前关注推送预览"邮件。
     * - 与日常到点推送内容一致，便于核对关注/报价/格式
     * - 不会推进 last_pushed_date，不影响每日定时推送
     * - 同一用户至少间隔 push.test-interval-seconds 秒才能再发（防连发）
     */
    @PostMapping("/test-send")
    public Result<String> testSend() {
        Long userId = getUserId();
        ensureConfigRow(userId);

        // 间隔校验
        Timestamp last = null;
        try {
            last = jdbcTemplate.queryForObject(
                "SELECT last_test_sent_at FROM push_config WHERE user_id = ?",
                Timestamp.class, userId);
        } catch (Exception ignore) { /* 列可能尚未存在/迁移 */ }
        if (last != null) {
            long diffMs = System.currentTimeMillis() - last.getTime();
            long intervalMs = testIntervalSeconds * 1000L;
            if (diffMs < intervalMs) {
                long waitSec = (intervalMs - diffMs + 999) / 1000;
                return Result.error("测试推送过于频繁，请 " + waitSec + " 秒后再试");
            }
        }

        // 调 Python 渲染并发送
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "python3", "/home/ubuntu/chemprice/push_digest.py", "test", String.valueOf(userId));
            pb.directory(new File("/home/ubuntu/chemprice"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out;
            try (InputStream is = p.getInputStream()) { out = is.readAllBytes(); }
            int code = p.waitFor();
            String stdout = new String(out, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (code != 0) {
                auditService.error("PUSH_SEND_TEST", "BUSINESS", "PUSH_CONFIG", String.valueOf(userId),
                    null, null, "测试推送发送失败：" + stdout);
                return Result.error("测试发送失败：" + stdout);
            }
            // stdout 首行格式: "OK <n>" | "NONE <msg>" | "ERROR <msg>"
            String first = stdout.split("\\s+", 2)[0];
            String rest = stdout.length() > first.length() ? stdout.substring(first.length()).trim() : "";
            if ("OK".equals(first)) {
                auditService.ok("PUSH_SEND_TEST", "BUSINESS", "PUSH_CONFIG", String.valueOf(userId),
                    null, null, "测试推送邮件已发送，共 " + (rest.isEmpty() ? "?" : rest) + " 条预览");
                return Result.ok("测试邮件已发送，共 " + (rest.isEmpty() ? "?" : rest) + " 条预览");
            } else if ("NONE".equals(first)) {
                auditService.warn("PUSH_SEND_TEST", "BUSINESS", "PUSH_CONFIG", String.valueOf(userId),
                    null, null, "测试推送无可推送的关注产品（" + rest + "）");
                return Result.error("当前没有可推送的关注产品（" + rest + "）");
            } else {
                auditService.ok("PUSH_SEND_TEST", "BUSINESS", "PUSH_CONFIG", String.valueOf(userId),
                    null, null, "测试推送邮件已发送");
                return Result.ok("测试邮件已发送");
            }
        } catch (Exception e) {
            auditService.error("PUSH_SEND_TEST", "BUSINESS", "PUSH_CONFIG", String.valueOf(userId),
                null, null, "测试推送异常：" + e.getMessage());
            return Result.error("测试发送异常：" + e.getMessage());
        }
    }
}
