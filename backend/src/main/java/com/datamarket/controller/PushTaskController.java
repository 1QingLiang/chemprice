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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关注产品邮件推送 - 任务化版本（2026-09-09）
 * 模型：每个用户可建 N 个推送任务（push_task），每个任务含若干报价行（push_task_item）。
 * 额度：push_config.push_quota = 跨任务共享商品条数上限（默认 3）；管理员不受限。
 * 发送：由 push_digest.py 按任务定时执行；本控制器负责任务管理与手动测试触发。
 */
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushTaskController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

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

    /** 当前用户是否管理员（额度不受限） */
    private boolean isAdminUser(Long userId) {
        try {
            String role = jdbcTemplate.queryForObject(
                "SELECT role FROM sys_user WHERE id = ?", String.class, userId);
            return "ADMIN".equals(role);
        } catch (Exception e) {
            return false;
        }
    }

    /** 懒初始化 push_config 行（额度载体：默认 3 个商品） */
    private void ensureConfigRow(Long userId) {
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM push_config WHERE user_id = ?", Integer.class, userId);
        if (exists == null || exists == 0) {
            jdbcTemplate.update(
                "INSERT INTO push_config (user_id, enabled, push_time, push_quota, updated_at) VALUES (?, 0, '17:30', 3, NOW())",
                userId);
        }
    }

    /** 读取某任务项明细 */
    private List<Map<String, Object>> loadTaskItems(Long taskId) {
        return jdbcTemplate.queryForList(
            "SELECT id, varieties_id AS varietiesId, varieties_name AS varietiesName, " +
            "COALESCE(market_name, '') AS marketName, COALESCE(specifications_name, '') AS specificationsName, " +
            "table_type AS tableType FROM push_task_item WHERE task_id = ? ORDER BY id", taskId);
    }

    /**
     * 我的推送任务列表 + 额度信息 + 可选报价行（关注列表）
     */
    @GetMapping("/tasks")
    public Result<Map<String, Object>> listTasks() {
        Long userId = getUserId();
        ensureConfigRow(userId);
        boolean unlimited = isAdminUser(userId);

        Map<String, Object> cfg = jdbcTemplate.queryForMap(
            "SELECT push_quota AS pushQuota FROM push_config WHERE user_id = ?", userId);
        int quota = cfg.get("pushQuota") == null ? 3 : ((Number) cfg.get("pushQuota")).intValue();

        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, name, enabled, send_time AS sendTime, last_sent_date AS lastSentDate, created_at AS createdAt " +
            "FROM push_task WHERE user_id = ? ORDER BY sort_no, id", userId);
        for (Map<String, Object> t : rows) {
            Long taskId = ((Number) t.get("id")).longValue();
            t.put("items", loadTaskItems(taskId));
            tasks.add(t);
        }

        // 候选行 = 该用户关注列表（含规格），供任务编辑器勾选
        // suggestSendTime「推荐推送时间」：该关注报价最新一条数据的入库时间（只取 HH:MM）。
        // 用户在任务编辑器里据此判断每日推送时间该设几点 —— 设得比这个早，收到的会是前一天的数据。
        // 用 CASE 按 tableType 选表 + 相关子查询，一条 SQL 完成（关注数少、子查询走索引）。
        // ⚠️ 价格表列是 utf8mb4_general_ci、user_favorite 是 utf8mb4_0900_ai_ci，
        //   列与列比较必须加 COLLATE（放右侧，避免左侧索引失效），否则报 1267。
        String sub = "(SELECT DATE_FORMAT(p.fetched_at, '%%H:%%i') FROM %s p " +
            "WHERE p.varieties_id=f.varieties_id " +
            "AND p.market_name=f.market_name COLLATE utf8mb4_general_ci " +
            "AND (f.specifications_name IS NULL OR f.specifications_name='' " +
            "     OR p.specifications_name=f.specifications_name COLLATE utf8mb4_general_ci) " +
            "AND p.middle_price >= 0 ORDER BY p.data_date DESC, p.id DESC LIMIT 1)";
        List<Map<String, Object>> favorites = jdbcTemplate.queryForList(
            "SELECT f.varieties_id AS varietiesId, c.name AS varietiesName, " +
            "COALESCE(f.market_name, '') AS marketName, COALESCE(f.specifications_name, '') AS specificationsName, " +
            "f.table_type AS tableType, " +
            "CASE f.table_type " +
            "  WHEN 'enterprise' THEN " + String.format(sub, "enterprise_price") + " " +
            "  WHEN 'international' THEN " + String.format(sub, "international_price") + " " +
            "  ELSE " + String.format(sub, "market_price") + " " +
            "END AS suggestSendTime " +
            "FROM user_favorite f " +
            "JOIN commodity c ON c.varieties_id = f.varieties_id " +
            "WHERE f.user_id = ? ORDER BY f.created_at DESC", userId);

        // 并集：任务里已有的报价行即使已被取消关注也必须可见（否则编辑保存会静默丢失这些项）
        Set<String> favKeys = new java.util.HashSet<>();
        for (Map<String, Object> f : favorites) {
            favKeys.add(favRowKey(f));
        }
        for (Map<String, Object> t : tasks) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) t.get("items");
            if (items == null) continue;
            for (Map<String, Object> it : items) {
                if (favKeys.add(favRowKey(it))) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("varietiesId", it.get("varietiesId"));
                    extra.put("varietiesName", it.get("varietiesName") == null ? "" : it.get("varietiesName"));
                    extra.put("marketName", it.get("marketName") == null ? "" : it.get("marketName"));
                    extra.put("specificationsName", it.get("specificationsName") == null ? "" : it.get("specificationsName"));
                    extra.put("tableType", it.get("tableType") == null ? "market" : it.get("tableType"));
                    extra.put("_orphan", true); // 标记：已不在关注列表，仅因任务引用而保留
                    favorites.add(extra);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quota", unlimited ? Integer.MAX_VALUE : quota);
        result.put("used", userItemCount(userId, null));
        result.put("unlimited", unlimited);
        result.put("tasks", tasks);
        result.put("favorites", favorites);
        return Result.ok(result);
    }

    /** 该用户全部（或排除某任务后）的任务商品条数 */
    private int userItemCount(Long userId, Long excludeTaskId) {
        String sql = "SELECT COUNT(*) FROM push_task_item i JOIN push_task t ON i.task_id = t.id " +
                     "WHERE t.user_id = ? " + (excludeTaskId == null ? "" : "AND t.id <> ?");
        Integer c;
        if (excludeTaskId == null) {
            c = jdbcTemplate.queryForObject(sql, Integer.class, userId);
        } else {
            c = jdbcTemplate.queryForObject(sql, Integer.class, userId, excludeTaskId);
        }
        return c == null ? 0 : c;
    }

    /** body 中 items 数组的元素个数 */
    private int bodyItemsCount(Map<String, Object> body) {
        Object items = body.get("items");
        if (!(items instanceof List)) return 0;
        return ((List<?>) items).size();
    }

    /** 候选行/任务项的联合主键（与前端 favKey 一致） */
    private String favRowKey(Map<String, Object> row) {
        return String.valueOf(row.get("varietiesId")) + "|" +
               (row.get("marketName") == null ? "" : row.get("marketName")) + "|" +
               (row.get("specificationsName") == null ? "" : row.get("specificationsName")) + "|" +
               (row.get("tableType") == null ? "market" : row.get("tableType"));
    }

    /**
     * 新建任务。
     * body: { name, enabled, sendTime, items: [{varietiesId, varietiesName, marketName, specificationsName, tableType}] }
     */
    @PostMapping("/tasks")
    @Transactional
    public Result<Map<String, Object>> createTask(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        ensureConfigRow(userId);

        String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
        if (name == null || name.isEmpty()) return Result.error("任务名称不能为空");
        if (name.length() > 50) return Result.error("任务名称过长（最多 50 字）");

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String sendTime = body.get("sendTime") == null ? "17:30" : String.valueOf(body.get("sendTime"));
        if (!ALLOWED_TIMES.contains(sendTime)) return Result.error("发送时间须在允许的档位内");

        // 任务名唯一
        Integer dup = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM push_task WHERE user_id = ? AND name = ?", Integer.class, userId, name);
        if (dup != null && dup > 0) return Result.error("已存在同名任务：" + name);

        // 额度校验（管理员不限）：额度 = 跨任务共享的「推送商品条数」上限
        boolean unlimited = isAdminUser(userId);
        int add = bodyItemsCount(body);
        if (!unlimited) {
            Integer quota = jdbcTemplate.queryForObject(
                "SELECT push_quota FROM push_config WHERE user_id = ?", Integer.class, userId);
            int q = quota == null ? 3 : quota;
            int otherUsed = userItemCount(userId, null);
            if (otherUsed + add > q) {
                return Result.error("推送商品额度不足：已用 " + otherUsed + " 条 / 共 " + q + " 条，本次新增 " + add +
                    " 条将超出上限（剩余 " + (q - otherUsed) + " 条可用），请减少商品或联系管理员开通更多额度");
            }
        }
        if (!unlimited && add == 0) {
            return Result.error("新建任务至少需要选择 1 条要推送的商品");
        }

        jdbcTemplate.update(
            "INSERT INTO push_task (user_id, name, enabled, send_time, sort_no) VALUES (?, ?, ?, ?, ?)",
            userId, name, enabled ? 1 : 0, sendTime, 0);
        Long taskId = jdbcTemplate.queryForObject(
            "SELECT id FROM push_task WHERE user_id = ? AND name = ?", Long.class, userId, name);

        replaceItems(taskId, body);

        auditService.ok("PUSH_TASK_CREATE", "BUSINESS", "PUSH_TASK", String.valueOf(taskId),
            null, null, "创建推送任务「" + name + "」（发送时间 " + sendTime
                + "，推送商品 " + bodyItemsCount(body) + " 条，状态" + (enabled ? "开启" : "关闭") + "）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", taskId);
        out.put("name", name);
        out.put("enabled", enabled);
        out.put("sendTime", sendTime);
        return Result.ok(out);
    }

    /** 全量替换任务项（items 为空则清空） */
    private void replaceItems(Long taskId, Map<String, Object> body) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("items", List.of());
        jdbcTemplate.update("DELETE FROM push_task_item WHERE task_id = ?", taskId);
        int sort = 0;
        for (Map<String, Object> it : items) {
            if (it.get("varietiesId") == null) continue;
            int varietiesId = ((Number) it.get("varietiesId")).intValue();
            String vname = it.get("varietiesName") == null ? null : String.valueOf(it.get("varietiesName"));
            String market = it.get("marketName") == null ? null : String.valueOf(it.get("marketName"));
            String specRaw = it.get("specificationsName") == null ? null : String.valueOf(it.get("specificationsName"));
            String spec = (specRaw == null || specRaw.isBlank()) ? null : specRaw.trim();
            String tt = it.get("tableType") == null ? "market" : String.valueOf(it.get("tableType"));
            // 名称缺失时补查
            if (vname == null || vname.isBlank()) {
                try {
                    vname = jdbcTemplate.queryForObject(
                        "SELECT name FROM commodity WHERE varieties_id = ?", String.class, varietiesId);
                } catch (Exception ignore) { vname = ""; }
            }
            jdbcTemplate.update(
                "INSERT IGNORE INTO push_task_item " +
                "(task_id, varieties_id, varieties_name, market_name, specifications_name, table_type, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                taskId, varietiesId, vname, (market == null || market.isBlank()) ? null : market, spec, tt);
        }
    }

    /** 更新任务（名称/开关/时间/项全量替换） */
    @PutMapping("/tasks/{id}")
    @Transactional
    public Result<String> updateTask(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        Integer mine = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM push_task WHERE id = ? AND user_id = ?", Integer.class, id, userId);
        if (mine == null || mine == 0) return Result.error("任务不存在");

        String nameBefore = jdbcTemplate.queryForObject(
            "SELECT name FROM push_task WHERE id = ?", String.class, id);
        java.util.List<String> changes = new ArrayList<>();

        if (body.containsKey("name")) {
            String name = String.valueOf(body.get("name")).trim();
            if (name.isEmpty()) return Result.error("任务名称不能为空");
            if (name.length() > 50) return Result.error("任务名称过长（最多 50 字）");
            Integer dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM push_task WHERE user_id = ? AND name = ? AND id <> ?",
                Integer.class, userId, name, id);
            if (dup != null && dup > 0) return Result.error("已存在同名任务：" + name);
            jdbcTemplate.update("UPDATE push_task SET name = ?, updated_at = NOW() WHERE id = ?", name, id);
            changes.add("名称: " + nameBefore + " → " + name);
        }
        if (body.containsKey("sendTime")) {
            String sendTime = String.valueOf(body.get("sendTime"));
            if (!ALLOWED_TIMES.contains(sendTime)) return Result.error("发送时间须在允许的档位内");
            jdbcTemplate.update("UPDATE push_task SET send_time = ?, updated_at = NOW() WHERE id = ?", sendTime, id);
            changes.add("发送时间: " + sendTime);
        }
        if (body.containsKey("enabled")) {
            boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
            jdbcTemplate.update("UPDATE push_task SET enabled = ?, updated_at = NOW() WHERE id = ?", enabled ? 1 : 0, id);
            changes.add(enabled ? "任务开启" : "任务停用");
        }
        if (body.containsKey("items")) {
            // 编辑保存：跨任务商品总额度校验（排除本任务已有占用）
            if (!isAdminUser(userId)) {
                int add = bodyItemsCount(body);
                Integer quota = jdbcTemplate.queryForObject(
                    "SELECT push_quota FROM push_config WHERE user_id = ?", Integer.class, userId);
                int q = quota == null ? 3 : quota;
                int otherUsed = userItemCount(userId, id);
                if (otherUsed + add > q) {
                    return Result.error("推送商品额度不足：其他任务已用 " + otherUsed + " 条 / 共 " + q + " 条，本任务本次设置 " + add +
                        " 条将超出上限（剩余 " + (q - otherUsed) + " 条可用），请减少商品或联系管理员开通更多额度");
                }
            }
            replaceItems(id, body);
            changes.add("推送商品改为 " + bodyItemsCount(body) + " 条");
        }
        auditService.ok("PUSH_TASK_UPDATE", "BUSINESS", "PUSH_TASK", String.valueOf(id),
            null, null, "修改推送任务「" + nameBefore + "」：" + String.join("；", changes));
        return Result.ok("保存成功");
    }

    /** 删除任务 */
    @DeleteMapping("/tasks/{id}")
    @Transactional
    public Result<String> deleteTask(@PathVariable Long id) {
        Long userId = getUserId();
        String taskName;
        try {
            taskName = jdbcTemplate.queryForObject(
                "SELECT name FROM push_task WHERE id = ? AND user_id = ?", String.class, id, userId);
        } catch (Exception e) {
            return Result.error("任务不存在");
        }
        jdbcTemplate.update(
            "DELETE i FROM push_task_item i JOIN push_task t ON i.task_id = t.id WHERE t.id = ? AND t.user_id = ?",
            id, userId);
        jdbcTemplate.update(
            "DELETE FROM push_task WHERE id = ? AND user_id = ?", id, userId);
        auditService.ok("PUSH_TASK_DELETE", "BUSINESS", "PUSH_TASK", String.valueOf(id),
            null, null, "删除推送任务「" + taskName + "」");
        return Result.ok("任务已删除");
    }

    /**
     * 测试发送指定任务：调 Python 渲染并发送一封预览邮件，不推进 last_sent_date。
     */
    @PostMapping("/tasks/{id}/test")
    public Result<String> testTask(@PathVariable Long id) {
        Long userId = getUserId();
        Integer mine = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM push_task WHERE id = ? AND user_id = ?", Integer.class, id, userId);
        if (mine == null || mine == 0) return Result.error("任务不存在");
        String taskName;
        try {
            taskName = jdbcTemplate.queryForObject(
                "SELECT name FROM push_task WHERE id = ?", String.class, id);
        } catch (Exception e) {
            taskName = String.valueOf(id);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "python3", "/home/ubuntu/chemprice/push_digest.py", "test-task", String.valueOf(id));
            pb.directory(new File("/home/ubuntu/chemprice"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out;
            try (InputStream is = p.getInputStream()) { out = is.readAllBytes(); }
            int code = p.waitFor();
            String stdout = new String(out, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (code != 0) {
                auditService.error("PUSH_TASK_TEST", "BUSINESS", "PUSH_TASK", String.valueOf(id),
                    null, null, "测试发送任务「" + taskName + "」失败：" + stdout);
                return Result.error("测试发送失败：" + stdout);
            }
            // 脚本 stdout 前部可能带 PUSH_OK 日志行，状态行恒为最后一行（"OK n" / "NONE ..." / "ERROR ..."）
            String[] outLines = stdout.split("\\r?\\n");
            String stateLine = outLines.length > 0 ? outLines[outLines.length - 1].trim() : "";
            String first = stateLine.split("\\s+", 2)[0];
            String rest = stateLine.length() > first.length() ? stateLine.substring(first.length()).trim() : "";
            if ("OK".equals(first)) {
                auditService.ok("PUSH_TASK_TEST", "BUSINESS", "PUSH_TASK", String.valueOf(id),
                    null, null, "测试发送任务「" + taskName + "」成功，共 " + (rest.isEmpty() ? "?" : rest) + " 条");
                return Result.ok("测试邮件已发送，共 " + (rest.isEmpty() ? "?" : rest) + " 条");
            } else if ("NONE".equals(first)) {
                auditService.warn("PUSH_TASK_TEST", "BUSINESS", "PUSH_TASK", String.valueOf(id),
                    null, null, "测试任务「" + taskName + "」无可推送报价（" + rest + "）");
                return Result.error("当前任务没有可推送的报价（" + rest + "）");
            }
            auditService.error("PUSH_TASK_TEST", "BUSINESS", "PUSH_TASK", String.valueOf(id),
                null, null, "测试发送任务「" + taskName + "」异常" + (stateLine.isEmpty() ? "" : "：" + stateLine));
            return Result.error("测试发送异常" + (stateLine.isEmpty() ? "" : "：" + stateLine));
        } catch (Exception e) {
            auditService.error("PUSH_TASK_TEST", "BUSINESS", "PUSH_TASK", String.valueOf(id),
                null, null, "测试发送任务「" + taskName + "」异常：" + e.getMessage());
            return Result.error("测试发送异常：" + e.getMessage());
        }
    }
}
