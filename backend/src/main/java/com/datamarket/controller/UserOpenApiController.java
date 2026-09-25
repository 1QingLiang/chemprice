package com.datamarket.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datamarket.common.Result;
import com.datamarket.common.TrendUtil;
import com.datamarket.entity.SysUser;
import com.datamarket.mapper.OpenApiMapper;
import com.datamarket.mapper.SysUserMapper;
import com.datamarket.security.OpenApiKeyFilter;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内「开放 API」自助管理（/api/open-api/**，JWT 登录态）。
 *
 * 额度模型（2026-09-21 积分制，开箱即用、无需管理员审批）：
 * - 所有注册用户默认可用；注册即送 200 积分
 * - 计时从「首次创建 API Key」开始，7 天后试用到期
 * - 计费：/commodities 每次 0.05 积分，其余接口每次 0.1 积分（/meta 免费）
 * - 管理员 / 已授权用户不受试用限制，走 daily_limit 日限额
 *
 * 多 Key：一个账号最多 MAX_KEYS 个，可分别 启停 / 改名 / 删除（均校验归属）。
 * Key 明文只在创建响应里出现一次，库中只存 SHA-256。
 */
@RestController
@RequestMapping("/api/open-api")
public class UserOpenApiController {

    private static final int MAX_KEYS = 5;
    private static final int TRIAL_DAYS = 7;
    private static final int NAME_MAX = 32;
    /** 新人赠送积分（仅用于前端展示进度，实际余额以 open_api_credits 为准） */
    private static final String TRIAL_CREDITS = "200";

    private final OpenApiMapper openApiMapper;
    private final SysUserMapper sysUserMapper;

    public UserOpenApiController(OpenApiMapper openApiMapper, SysUserMapper sysUserMapper) {
        this.openApiMapper = openApiMapper;
        this.sysUserMapper = sysUserMapper;
    }

    /** 我的档位 / 额度 / 积分 / Key 列表 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        SysUser u = currentUser();
        if (u == null) {
            return Result.error(401, "登录状态已失效");
        }
        int uid = u.getId().intValue();
        Map<String, Object> p = openApiMapper.openApiProfile(uid);

        boolean admin = "ADMIN".equals(u.getRole());
        boolean authorized = p != null && asInt(p.get("enabled")) == 1;
        boolean trial = !admin && !authorized;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);              // 开箱即用
        out.put("admin", admin);
        out.put("authorized", authorized);
        out.put("trial", trial);
        out.put("maxKeys", MAX_KEYS);
        out.put("trialDays", TRIAL_DAYS);
        out.put("creditsTotal", TRIAL_CREDITS);
        out.put("pricing", Map.of(
                "search", "0.05",
                "default", "0.10",
                "meta", "0"));
        if (p != null) {
            out.put("credits", p.get("credits"));
            out.put("dailyLimit", p.get("dailyLimit"));
            out.put("trialStart", p.get("trialStart"));
            out.put("trialEnd", p.get("trialEnd"));
            out.put("trialExpired", p.get("trialExpired"));
            out.put("trialDaysLeft", p.get("trialDaysLeft"));
        }
        List<Map<String, Object>> keys = openApiMapper.findKeysByUser(uid);
        out.put("keys", keys);
        return Result.ok(out);
    }

    /** 新建 Key（首次创建即启动 7 天试用计时） */
    @PostMapping("/key")
    public Result<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        SysUser u = currentUser();
        if (u == null) {
            return Result.error(401, "登录状态已失效");
        }
        int uid = u.getId().intValue();
        int have = openApiMapper.countKeysByUser(uid);
        if (have >= MAX_KEYS) {
            return Result.error(400, "最多创建 " + MAX_KEYS + " 个 Key，如需更多请联系客服");
        }
        String name = body == null || body.get("name") == null
                ? "" : String.valueOf(body.get("name")).trim();
        if (name.isEmpty()) {
            name = "Key-" + (have + 1);
        }
        if (name.length() > NAME_MAX) {
            name = name.substring(0, NAME_MAX);
        }

        // 首次建 Key → 写入试用起点（只写一次，永不重置）
        openApiMapper.startTrialOnce(uid);

        Map<String, Object> p = openApiMapper.openApiProfile(uid);
        int limit = p != null && p.get("dailyLimit") != null
                ? ((Number) p.get("dailyLimit")).intValue() : 1000;

        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        String raw = "cpk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        openApiMapper.insertUserKey(name, OpenApiKeyFilter.sha256(raw),
                raw.substring(0, 8), limit, uid);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", raw);
        out.put("name", name);
        out.put("notice", "Key 只显示这一次，请立即保存；系统中仅存哈希");
        return Result.ok(out);
    }

    /** 我的使用看板：今日/累计用量、积分、近 N 天日增长趋势 */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(
            @RequestParam(defaultValue = "14") Integer days) {
        SysUser u = currentUser();
        if (u == null) {
            return Result.error(401, "登录状态已失效");
        }
        int uid = u.getId().intValue();
        int d = Math.max(7, Math.min(90, days == null ? 14 : days));

        Map<String, Object> p = openApiMapper.openApiProfile(uid);
        boolean admin = "ADMIN".equals(u.getRole());
        boolean authorized = p != null && asInt(p.get("enabled")) == 1;

        List<Map<String, Object>> trend = TrendUtil.buildTrend(openApiMapper.dailyByUser(uid, d - 1), d);
        Map<String, Object> today = TrendUtil.pickToday(trend);
        Map<String, Object> cum = openApiMapper.totalsByUser(uid);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "user");
        out.put("tier", admin ? "admin" : (authorized ? "authorized" : "trial"));
        out.put("days", d);
        out.put("trend", trend);
        out.put("todayCalls", today.get("calls"));
        out.put("todayCredits", today.get("credits"));
        out.put("dailyCalls", cum == null ? 0 : cum.get("calls"));
        out.put("dailyCredits", cum == null ? 0 : cum.get("credits"));
        if (p != null) {
            out.put("credits", p.get("credits"));
            out.put("trialStart", p.get("trialStart"));
            out.put("trialEnd", p.get("trialEnd"));
            out.put("trialExpired", p.get("trialExpired"));
            out.put("trialDaysLeft", p.get("trialDaysLeft"));
        }
        // Key 侧的历史累计（权威，不受日统计表建表时间限制）
        long keyTotal = 0;
        for (Map<String, Object> k : openApiMapper.findKeysByUser(uid)) {
            Object v = k.get("totalCalls");
            if (v instanceof Number n) {
                keyTotal += n.longValue();
            }
        }
        out.put("totalCalls", keyTotal);
        return Result.ok(out);
    }

    /** 改名 / 启用停用指定 Key */
    @PutMapping("/key/{id}")
    public Result<Object> update(@PathVariable int id, @RequestBody(required = false) Map<String, Object> body) {
        SysUser u = currentUser();
        if (u == null) {
            return Result.error(401, "登录状态已失效");
        }
        int uid = u.getId().intValue();
        if (openApiMapper.findKeyByIdAndUser(id, uid) == null) {
            return Result.error(404, "Key 不存在");
        }
        if (body != null && body.get("name") != null) {
            String nm = String.valueOf(body.get("name")).trim();
            if (nm.length() > NAME_MAX) {
                nm = nm.substring(0, NAME_MAX);
            }
            if (!nm.isEmpty()) {
                openApiMapper.renameKey(id, uid, nm);
            }
        }
        if (body != null && body.get("status") != null) {
            Object sv = body.get("status");
            boolean on = Boolean.TRUE.equals(sv) || "1".equals(String.valueOf(sv));
            openApiMapper.setKeyStatus(id, uid, on ? 1 : 0);
        }
        return Result.ok("已更新");
    }

    /** 删除指定 Key */
    @DeleteMapping("/key/{id}")
    public Result<Object> delete(@PathVariable int id) {
        SysUser u = currentUser();
        if (u == null) {
            return Result.error(401, "登录状态已失效");
        }
        int uid = u.getId().intValue();
        if (openApiMapper.deleteKeyByIdAndUser(id, uid) == 0) {
            return Result.error(404, "Key 不存在");
        }
        return Result.ok("已删除");
    }

    /* ---- 工具 ---- */

    private static int asInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private SysUser currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return sysUserMapper.selectOne(new QueryWrapper<SysUser>().eq("username", auth.getName()));
    }
}
