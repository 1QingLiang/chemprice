package com.datamarket.controller;

import com.datamarket.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全站公告 v2（2026-09-17 增强）
 *
 * - GET    /api/announcement/current            登录用户：当前生效公告（尊重时间窗）
 * - POST   /api/admin/announcement/publish      ADMIN：发布（自动停用其他）
 * - PUT    /api/admin/announcement/{id}         ADMIN：编辑
 * - POST   /api/admin/announcement/{id}/enable  ADMIN：重新启用（自动停用其他）
 * - POST   /api/admin/announcement/{id}/disable ADMIN：停用
 * - DELETE /api/admin/announcement/{id}         ADMIN：删除（物理删除）
 * - GET    /api/admin/announcement/list         ADMIN：搜索 + 分页
 *
 * 说明：level 决定顶部走马灯的配色（normal 蓝 / important 橙红 / maintenance 紫）。
 * start_at / end_at 为空表示不限制；到点自动上线、过期自动下线，无需人工干预。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AnnouncementController {

    private final JdbcTemplate jdbcTemplate;

    /** 允许的公告级别 */
    private static final Set<String> LEVELS = Set.of("normal", "important", "maintenance");

    private static String normLevel(String s) {
        if (s == null) return "normal";
        String v = s.trim().toLowerCase();
        return LEVELS.contains(v) ? v : "normal";
    }

    /** 空串归一成 null（避免 '' 与 NULL 语义分裂） */
    private static String nz(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 当前生效的公告（enabled=1 且落在时间窗内，取最新一条） */
    @GetMapping("/api/announcement/current")
    public Result<Map<String, Object>> current() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, content, level, start_at, end_at, created_at FROM announcement " +
                " WHERE enabled=1 " +
                "   AND (start_at IS NULL OR start_at <= NOW()) " +
                "   AND (end_at   IS NULL OR end_at   >= NOW()) " +
                " ORDER BY id DESC LIMIT 1");
        if (rows.isEmpty()) return Result.ok(null);
        return Result.ok(rows.get(0));
    }

    /** 发布新公告：自动停用其他（走马灯只展示一条） */
    @PostMapping("/api/admin/announcement/publish")
    public Result<Map<String, Object>> publish(@RequestBody Map<String, String> body, Authentication auth) {
        if (body == null) return Result.error("参数不能为空");
        String content = body.get("content");
        if (content == null || content.isBlank()) return Result.error("公告内容不能为空");
        content = content.trim();
        if (content.length() > 500) return Result.error("公告内容不能超过 500 字");

        String level = normLevel(body.get("level"));
        String startAt = nz(body.get("startAt"));
        String endAt = nz(body.get("endAt"));
        if (startAt != null && endAt != null && startAt.compareTo(endAt) > 0) {
            return Result.error("生效时间不能晚于失效时间");
        }

        String by = auth != null ? auth.getName() : "admin";
        jdbcTemplate.update("UPDATE announcement SET enabled=0 WHERE enabled=1");
        jdbcTemplate.update(
                "INSERT INTO announcement(content, level, enabled, start_at, end_at, created_by) " +
                "VALUES (?, ?, 1, ?, ?, ?)", content, level, startAt, endAt, by);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM announcement", Long.class);
        log.info("发布公告 id={} level={} by={} len={}", id, level, by, content.length());
        return Result.ok(Map.of("id", id == null ? 0L : id));
    }

    /** 编辑公告内容 / 级别 / 时间窗（不改 enabled） */
    @PutMapping("/api/admin/announcement/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        if (body == null) return Result.error("参数不能为空");
        String content = body.get("content");
        if (content == null || content.isBlank()) return Result.error("公告内容不能为空");
        content = content.trim();
        if (content.length() > 500) return Result.error("公告内容不能超过 500 字");

        String level = normLevel(body.get("level"));
        String startAt = nz(body.get("startAt"));
        String endAt = nz(body.get("endAt"));
        if (startAt != null && endAt != null && startAt.compareTo(endAt) > 0) {
            return Result.error("生效时间不能晚于失效时间");
        }

        int n = jdbcTemplate.update(
                "UPDATE announcement SET content=?, level=?, start_at=?, end_at=? WHERE id=?",
                content, level, startAt, endAt, id);
        if (n == 0) return Result.error("公告不存在");
        log.info("编辑公告 id={} level={}", id, level);
        return Result.ok(null);
    }

    /** 重新启用历史公告（自动停用其他） */
    @PostMapping("/api/admin/announcement/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM announcement WHERE id=?", Integer.class, id);
        if (cnt == null || cnt == 0) return Result.error("公告不存在");
        jdbcTemplate.update("UPDATE announcement SET enabled=0 WHERE enabled=1");
        jdbcTemplate.update("UPDATE announcement SET enabled=1 WHERE id=?", id);
        log.info("重新启用公告 id={}", id);
        return Result.ok(null);
    }

    /** 停用 */
    @PostMapping("/api/admin/announcement/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        jdbcTemplate.update("UPDATE announcement SET enabled=0 WHERE id=?", id);
        log.info("停用公告 id={}", id);
        return Result.ok(null);
    }

    /** 删除（物理删除，不可恢复） */
    @DeleteMapping("/api/admin/announcement/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        int n = jdbcTemplate.update("DELETE FROM announcement WHERE id=?", id);
        if (n == 0) return Result.error("公告不存在");
        log.info("删除公告 id={}", id);
        return Result.ok(null);
    }

    /** 搜索 + 分页列表 */
    @GetMapping("/api/admin/announcement/list")
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(50, size));

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND content LIKE ? ");
            args.add("%" + keyword.trim() + "%");
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM announcement" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, content, level, enabled, start_at, end_at, created_by, created_at, updated_at " +
                "  FROM announcement" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("total", total == null ? 0L : total);
        out.put("page", page);
        out.put("size", size);
        return Result.ok(out);
    }
}
