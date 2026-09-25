package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.common.TrendUtil;
import com.datamarket.mapper.OpenApiMapper;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员：开放 API 授权与额度管理（/api/admin/open-api/**）。
 * 权限由 SecurityConfig 的 /api/admin/** hasRole("ADMIN") 保证，这里不做重复校验。
 *
 * 开通 = sys_user.open_api_enabled=1 → 该用户脱离试用限制，改走 daily_limit 日限额；
 * 关闭 = 该用户的全部 key 立即失效（status=0）。
 * 积分充值 / 调整试用有效期用于「更多额度联系客服」的场景。
 */
@RestController
@RequestMapping("/api/admin/open-api")
public class AdminOpenApiController {

    /** 试用期天数（与 OpenApiKeyFilter / UserOpenApiController 保持一致） */
    private static final int TRIAL_DAYS = 7;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpenApiMapper openApiMapper;

    public AdminOpenApiController(OpenApiMapper openApiMapper) {
        this.openApiMapper = openApiMapper;
    }

    /** 全部用户的授权状态、积分、试用期与 key 汇总（前端自行搜索过滤） */
    @GetMapping("/overview")
    public Result<List<Map<String, Object>>> overview() {
        return Result.ok(openApiMapper.adminOverview());
    }

    /** 全站使用看板：汇总卡片 + 近 N 天日增长趋势 */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(
            @RequestParam(defaultValue = "14") Integer days) {
        int d = Math.max(7, Math.min(90, days == null ? 14 : days));

        List<Map<String, Object>> rows = openApiMapper.adminOverview();
        List<Map<String, Object>> trend = TrendUtil.buildTrend(openApiMapper.dailyAll(d - 1), d);
        Map<String, Object> today = TrendUtil.pickToday(trend);
        Map<String, Object> cum = openApiMapper.totalsAll();

        long users = rows.size();
        long enabledUsers = rows.stream().filter(r -> num(r.get("enabled")) == 1).count();
        long activeKeys = rows.stream().mapToLong(r -> num(r.get("activeKeyCount"))).sum();
        long allKeys = rows.stream().mapToLong(r -> num(r.get("keyCount"))).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "admin");
        out.put("days", d);
        out.put("totalUsers", users);
        out.put("enabledUsers", enabledUsers);
        out.put("trialUsers", users - enabledUsers);
        out.put("activeKeys", activeKeys);
        out.put("allKeys", allKeys);
        out.put("todayCalls", today.get("calls"));
        out.put("todayCredits", today.get("credits"));
        out.put("dailyCalls", cum == null ? 0 : cum.get("calls"));
        out.put("dailyCredits", cum == null ? 0 : cum.get("credits"));
        // 总使用次数取 key 上的权威累计值（不受日统计表建表时间限制）
        out.put("totalCalls", openApiMapper.grandTotalCalls());
        out.put("trend", trend);
        return Result.ok(out);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 开通/关闭授权、设日限额、积分充值、调整试用有效期。
     * <p>
     * body 可选字段（全部独立生效，传哪个处理哪个）：
     * <ul>
     *   <li><code>enabled</code> + <code>dailyLimit</code>：开通/关闭授权（开通即脱离试用档）</li>
     *   <li><code>addCredits</code>：积分增量充值，可为负做冲正</li>
     *   <li><code>credits</code>：把积分余额直接置为该值</li>
     *   <li><code>extendDays</code>：把试用到期日调整为「今天 + N 天」（传 0 = 立即到期）</li>
     * </ul>
     */
    @PutMapping("/users/{userId}")
    public Result<Object> update(@PathVariable int userId, @RequestBody Map<String, Object> body) {
        boolean touched = false;

        Object e = body.get("enabled");
        if (e != null) {
            String ev = String.valueOf(e);
            boolean enabled = Boolean.TRUE.equals(e) || "1".equals(ev) || "true".equalsIgnoreCase(ev);
            int limit = 1000;
            Object l = body.get("dailyLimit");
            if (l instanceof Number n) {
                limit = Math.max(1, Math.min(100000, n.intValue()));
            }
            openApiMapper.updateUserOpenApi(userId, enabled ? 1 : 0, limit);
            if (enabled) {
                openApiMapper.enableKeysOfUser(userId, limit);   // 恢复既有 key 并同步限额
            } else {
                openApiMapper.disableKeysOfUser(userId);         // 关闭 → key 立即失效
            }
            touched = true;
        }

        Object ac = body.get("addCredits");
        if (ac instanceof Number n) {
            openApiMapper.addCredits(userId, new BigDecimal(n.toString()));
            touched = true;
        }

        Object sc = body.get("credits");
        if (sc instanceof Number n) {
            openApiMapper.setCredits(userId, new BigDecimal(n.toString()));
            touched = true;
        }

        Object ed = body.get("extendDays");
        if (ed instanceof Number n) {
            int days = Math.max(0, Math.min(3650, n.intValue()));
            // 使 到期日 = 今天 + days  →  起点 = 今天 - (TRIAL_DAYS - days)
            LocalDateTime start = LocalDateTime.now().minusDays(TRIAL_DAYS - days);
            openApiMapper.setTrialStart(userId, start.format(FMT));
            touched = true;
        }

        return Result.ok(touched ? "已更新" : "无改动");
    }
}
