package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.security.PermissionHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知：认证结果、供需被查看、报价更新提醒等都走 site_message 表。
 * 列表只返回本人消息；已读操作带 user_id 条件，防止越权改别人的。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final JdbcTemplate jdbcTemplate;
    private final PermissionHelper permissionHelper;

    private Long getUserId() {
        return permissionHelper.getCurrentUserId();
    }

    /** 未读数（头部铃铛角标，轮询用） */
    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        Long uid = getUserId();
        if (uid == null) return Result.error(401, "未登录");
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM site_message WHERE user_id = ? AND is_read = 0", Long.class, uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", n == null ? 0L : n);
        return Result.ok(out);
    }

    /** 最近 50 条 */
    @GetMapping
    public Result<Map<String, Object>> list() {
        Long uid = getUserId();
        if (uid == null) return Result.error(401, "未登录");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, title, content, type, is_read, created_at FROM site_message " +
                "WHERE user_id = ? ORDER BY id DESC LIMIT 50", uid);
        Long unread = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM site_message WHERE user_id = ? AND is_read = 0", Long.class, uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("unread", unread == null ? 0L : unread);
        return Result.ok(out);
    }

    /** 标记单条已读 */
    @PostMapping("/{id}/read")
    public Result<String> read(@PathVariable Long id) {
        Long uid = getUserId();
        if (uid == null) return Result.error(401, "未登录");
        jdbcTemplate.update("UPDATE site_message SET is_read = 1 WHERE id = ? AND user_id = ?", id, uid);
        return Result.ok("已读");
    }

    /** 全部已读 */
    @PostMapping("/read-all")
    public Result<String> readAll() {
        Long uid = getUserId();
        if (uid == null) return Result.error(401, "未登录");
        jdbcTemplate.update("UPDATE site_message SET is_read = 1 WHERE user_id = ?", uid);
        return Result.ok("已全部标记为已读");
    }
}
