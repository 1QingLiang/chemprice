package com.datamarket.controller;

import com.datamarket.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final JdbcTemplate jdbcTemplate;

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return jdbcTemplate.queryForObject(
            "SELECT id FROM sys_user WHERE username = ?", Long.class, auth.getName());
    }

    /** 获取用户关注列表 */
    @GetMapping
    public Result<List<Map<String, Object>>> getFavorites() {
        Long userId = getUserId();
        List<Map<String, Object>> favs = jdbcTemplate.queryForList(
            "SELECT f.varieties_id, c.name, f.market_name, " +
            "COALESCE(f.specifications_name, '') AS specifications_name, f.table_type " +
            "FROM user_favorite f JOIN commodity c ON f.varieties_id = c.varieties_id " +
            "WHERE f.user_id = ? ORDER BY f.created_at DESC", userId);
        return Result.ok(favs);
    }

    /** 添加关注：写 user_favorite + 查最新价格写 user_favorite_price（粒度=商品+报价点+规格+类型） */
    @PostMapping("/{varietiesId}")
    public Result<String> addFavorite(
            @PathVariable Integer varietiesId,
            @RequestParam(defaultValue = "") String marketName,
            @RequestParam(defaultValue = "") String specificationsName,
            @RequestParam(defaultValue = "market") String tableType) {
        Long userId = getUserId();

        // 空规格统一存 NULL（避免 '' 与 NULL 语义分裂）
        String spec = (specificationsName == null || specificationsName.isBlank()) ? null : specificationsName.trim();

        // 1. 写关注关系
        // ⚠️ 唯一键含 specifications_name 且常为 NULL，MySQL 唯一索引里 NULL != NULL
        // → INSERT IGNORE 挡不住重复关注，改为「先查后插」保证幂等
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_favorite WHERE user_id = ? AND varieties_id = ? " +
            "AND market_name = ? AND table_type = ? AND (specifications_name <=> ?)",
            Integer.class, userId, varietiesId, marketName, tableType, spec);
        if (exists == null || exists == 0) {
            jdbcTemplate.update(
                "INSERT INTO user_favorite (user_id, varieties_id, market_name, specifications_name, table_type) " +
                "VALUES (?, ?, ?, ?, ?)",
                userId, varietiesId, marketName, spec, tableType);
        }

        // 2. 从对应价格表按【规格】查最新数据，写入快照表
        String tbl = Map.of("market", "market_price", "enterprise", "enterprise_price", "international", "international_price")
                .getOrDefault(tableType, "market_price");
        String name = jdbcTemplate.queryForObject(
            "SELECT name FROM commodity WHERE varieties_id = ?", String.class, varietiesId);

        String specCond = (spec == null)
            ? " AND (specifications_name IS NULL OR specifications_name = '') "
            : " AND specifications_name = ? ";
        List<Map<String, Object>> rows = (spec == null)
            ? jdbcTemplate.queryForList(
                "SELECT middle_price, high_price, low_price, data_date, data_rise_or_fall, " +
                "unit_valuation_name, data_rate, specifications_name FROM " + tbl +
                " WHERE varieties_id = ? AND market_name = ? AND middle_price IS NOT NULL" + specCond +
                " ORDER BY data_date DESC LIMIT 1", varietiesId, marketName)
            : jdbcTemplate.queryForList(
                "SELECT middle_price, high_price, low_price, data_date, data_rise_or_fall, " +
                "unit_valuation_name, data_rate, specifications_name FROM " + tbl +
                " WHERE varieties_id = ? AND market_name = ? AND middle_price IS NOT NULL" + specCond +
                " ORDER BY data_date DESC LIMIT 1", varietiesId, marketName, spec);

        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            String snapSpec = r.get("specifications_name") == null ? null : String.valueOf(r.get("specifications_name"));
            // ⚠️ 不能用 ON DUPLICATE KEY UPDATE：唯一键含 specifications_name，而它常为 NULL，
            // MySQL 唯一索引中 NULL != NULL → 冲突不触发 → 重复关注会插出多行。
            // 改用「先删同组（<=> NULL 安全比较）再插入」。
            jdbcTemplate.update(
                "DELETE FROM user_favorite_price WHERE user_id = ? AND varieties_id = ? " +
                "AND market_name = ? AND table_type = ? AND (specifications_name <=> ?)",
                userId, varietiesId, marketName, tableType, snapSpec);
            jdbcTemplate.update(
                "INSERT INTO user_favorite_price (user_id, varieties_id, varieties_name, market_name, specifications_name, table_type, " +
                "middle_price, high_price, low_price, data_rate, data_rise_or_fall, unit_valuation_name, data_date) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, varietiesId, name, marketName, snapSpec, tableType,
                r.get("middle_price"), r.get("high_price"), r.get("low_price"),
                r.get("data_rate"), r.get("data_rise_or_fall"),
                r.get("unit_valuation_name"), r.get("data_date"));
        }

        return Result.ok("关注成功");
    }

    /** 取消关注：删 user_favorite + 删 user_favorite_price（需同规格才删） */
    @DeleteMapping("/{varietiesId}")
    public Result<String> removeFavorite(
            @PathVariable Integer varietiesId,
            @RequestParam(defaultValue = "") String marketName,
            @RequestParam(defaultValue = "") String specificationsName,
            @RequestParam(defaultValue = "market") String tableType) {
        Long userId = getUserId();
        String spec = (specificationsName == null || specificationsName.isBlank()) ? null : specificationsName.trim();
        if (spec == null) {
            jdbcTemplate.update(
                "DELETE FROM user_favorite WHERE user_id = ? AND varieties_id = ? AND market_name = ? AND table_type = ? AND specifications_name IS NULL",
                userId, varietiesId, marketName, tableType);
            jdbcTemplate.update(
                "DELETE FROM user_favorite_price WHERE user_id = ? AND varieties_id = ? AND market_name = ? AND table_type = ? AND specifications_name IS NULL",
                userId, varietiesId, marketName, tableType);
        } else {
            jdbcTemplate.update(
                "DELETE FROM user_favorite WHERE user_id = ? AND varieties_id = ? AND market_name = ? AND specifications_name = ? AND table_type = ?",
                userId, varietiesId, marketName, spec, tableType);
            jdbcTemplate.update(
                "DELETE FROM user_favorite_price WHERE user_id = ? AND varieties_id = ? AND market_name = ? AND specifications_name = ? AND table_type = ?",
                userId, varietiesId, marketName, spec, tableType);
        }
        return Result.ok("取消关注");
    }

    /** 查快照表：首页关注商品最新价格 */
    /**
     * 查快照表：首页「关注商品最新价格」。
     * <p>
     * ⚠️ 必须按 (品种, 报价点, 类型, 规格) 去重取最新一条：
     * 快照表唯一键含 specifications_name，而该列常为 NULL，MySQL 唯一索引中 NULL != NULL
     * → 历史上有重复行（曾导致首页同一关注项显示成两张卡片）。
     * 去重同时兜住脏数据与未来可能的重复写入。
     */
    @GetMapping("/latest-prices")
    public Result<List<Map<String, Object>>> getFavoriteLatestPrices() {
        Long userId = getUserId();
        List<Map<String, Object>> prices = jdbcTemplate.queryForList(
            "SELECT varieties_id, name, market_name, specifications_name, table_type, " +
            "       middle_price, high_price, low_price, data_rate, data_rise_or_fall, " +
            "       unit_valuation_name, data_date, created_at " +
            "  FROM ( " +
            "    SELECT varieties_id, varieties_name AS name, market_name, " +
            "           COALESCE(specifications_name, '') AS specifications_name, table_type, " +
            "           middle_price, high_price, low_price, data_rate, data_rise_or_fall, " +
            "           unit_valuation_name, data_date, created_at, " +
            "           ROW_NUMBER() OVER (PARTITION BY varieties_id, market_name, table_type, " +
            "                              COALESCE(specifications_name, '') " +
            "                              ORDER BY data_date DESC, id DESC) AS rn " +
            "      FROM user_favorite_price WHERE user_id = ? " +
            "  ) t WHERE rn = 1 ORDER BY created_at DESC", userId);
        return Result.ok(prices);
    }
}
