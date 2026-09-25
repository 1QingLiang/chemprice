package com.datamarket.security;

import com.datamarket.mapper.OpenApiMapper;
import com.datamarket.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Map;

/**
 * 开放 API（/api/open/**）的 API Key 鉴权过滤器。
 *
 * 职责：
 * 1. 校验请求头 X-API-Key（库中只存 SHA-256，明文只在创建时显示一次）
 * 2. 按账号档位计费 / 校验额度
 *
 * 档位与额度（2026-09-21 积分制）：
 * - 管理员（role=ADMIN）      → 不限额度
 * - 已授权用户（enabled=1）   → 按 key 的 daily_limit 日限额
 * - 试用用户（其余注册用户）  → 200 积分，自「首次建 Key」起 7 天有效
 *
 * 积分单价：/commodities = 0.05，其余接口 = 0.1，/meta = 免费（连通性自检不该扣分）
 * 扣分用原子 UPDATE（余额不足影响行数=0），并发下不会超扣。
 */
@Component
@Slf4j
public class OpenApiKeyFilter extends OncePerRequestFilter {

    private final OpenApiMapper mapper;
    private final AuditService auditService;

    /** 品种搜索单价 */
    private static final BigDecimal COST_SEARCH = new BigDecimal("0.05");
    /** 其余接口单价 */
    private static final BigDecimal COST_DEFAULT = new BigDecimal("0.10");
    /** 自检端点免费（不返回行情数据，仅用于探活与鉴权自检） */
    private static final BigDecimal COST_FREE = BigDecimal.ZERO;

    private static final int TRIAL_DAYS = 7;

    public OpenApiKeyFilter(OpenApiMapper mapper, AuditService auditService) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /**
     * 记录一条开放 API 调用明细（写入 audit_log，operation_type=OPENAPI）。
     * <p>本过滤器在 DispatcherServlet 之前执行，RequestContextHolder 尚未绑定，
     * 而 AuditService 取 IP/URI 正是依赖它 → 需临时绑定、用完即清。
     */
    private void auditCall(HttpServletRequest req, Map<String, Object> k, int keyId, BigDecimal cost) {
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
            String uname = k.get("username") == null ? null : String.valueOf(k.get("username"));
            String keyName = k.get("name") == null ? "" : String.valueOf(k.get("name"));
            String c = cost == null ? "0" : cost.toPlainString();
            auditService.okAs("OPENAPI_CALL", "OPENAPI", "OPENAPI", String.valueOf(keyId),
                    null, c,
                    "调用开放API：" + req.getRequestURI() + "（Key：" + keyName + "，计费 " + c + " 积分）",
                    uname);
        } catch (Exception e) {
            log.debug("开放API调用日志写入失败: {}", e.getMessage());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/open/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        if (key == null || key.isBlank()) {
            deny(res, 401, "缺少 API Key：请在请求头携带 X-API-Key");
            return;
        }
        key = key.trim();
        if (key.length() < 16 || key.length() > 128) {
            deny(res, 401, "API Key 无效");
            return;
        }
        Map<String, Object> k = mapper.findKeyByHash(sha256(key));
        if (k == null || k.get("status") == null || ((Number) k.get("status")).intValue() != 1) {
            deny(res, 401, "API Key 无效或已停用");
            return;
        }
        // key 归属的站内用户被禁用 → key 一并失效（管理员手建的 key user_id 为空，不受影响）
        Object us = k.get("userStatus");
        if (us != null && ((Number) us).intValue() == 0) {
            deny(res, 401, "API Key 无效或已停用");
            return;
        }

        int id = ((Number) k.get("id")).intValue();
        Object uidObj = k.get("userId");
        boolean isAdmin = "ADMIN".equals(k.get("userRole"));
        boolean authorized = k.get("userEnabled") != null
                && ((Number) k.get("userEnabled")).intValue() == 1;
        BigDecimal cost = costOf(req.getRequestURI());

        // ---- 试用用户：有归属账号，且既非管理员也未获授权 ----
        if (uidObj != null && !isAdmin && !authorized) {
            int uid = ((Number) uidObj).intValue();
            Object expired = k.get("trialExpired");
            if (expired != null && ((Number) expired).intValue() == 1) {
                deny(res, 429, "试用已到期（" + TRIAL_DAYS + " 天），更多额度请联系客服");
                return;
            }
            if (cost.signum() > 0 && mapper.deductCredits(uid, cost) == 0) {
                deny(res, 429, "试用积分已用完，更多额度请联系客服");
                return;
            }
            mapper.bumpUsage(id);
            mapper.bumpDaily(uid, cost);          // 记当日用量 + 消耗积分
            auditCall(req, k, id, cost);          // 记调用明细
            req.setAttribute("openApiKeyName", k.get("name"));
            chain.doFilter(req, res);
            return;
        }

        // ---- 管理员 / 已授权用户 / 管理员手工建的 key：走日限额 ----
        int limit = ((Number) k.get("dailyLimit")).intValue();
        int used = ((Number) k.get("callsToday")).intValue();
        String callDate = (String) k.get("callDate");
        int usedNow = LocalDate.now().toString().equals(callDate) ? used + 1 : 1;
        if (usedNow > limit) {
            deny(res, 429, "今日调用额度已用完（上限 " + limit + " 次/日）");
            return;
        }
        mapper.bumpUsage(id);
        // 不扣积分，但同样计入当日用量（credits 记 0）；无归属 key 归到 user_id=0
        mapper.bumpDaily(uidObj == null ? 0 : ((Number) uidObj).intValue(), BigDecimal.ZERO);
        auditCall(req, k, id, BigDecimal.ZERO);   // 记调用明细
        req.setAttribute("openApiKeyName", k.get("name"));
        chain.doFilter(req, res);
    }

    /** 按请求路径取单价（末尾斜杠已归一化） */
    private static BigDecimal costOf(String uri) {
        String p = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        if (p.endsWith("/meta")) {
            return COST_FREE;
        }
        if (p.endsWith("/commodities")) {
            return COST_SEARCH;
        }
        return COST_DEFAULT;
    }

    private void deny(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":" + status + ",\"message\":\"" + msg + "\"}");
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
