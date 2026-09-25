package com.datamarket.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datamarket.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.datamarket.entity.AuditLog;
import com.datamarket.entity.SysUser;
import com.datamarket.mapper.AuditLogMapper;
import com.datamarket.mapper.SysUserMapper;
import com.datamarket.service.AuditService;
import com.datamarket.service.VerifyNotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.time.LocalDate;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SysUserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final VerifyNotifyService verifyNotify;

    // ========== 供应商认证审核 ==========

    /** 认证申请列表 */
    @GetMapping("/supplier-verifies")
    public Result<Map<String, Object>> supplierVerifies(
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        int p = Math.max(1, page);
        int sz = Math.max(1, Math.min(100, size));

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            if ("rejected".equals(status)) {
                where.append(" AND v.status IN ('rejected','reverify','revoked')");
            } else {
                where.append(" AND v.status = ?");
                args.add(status);
            }
        }
        String whereSql = where.toString();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM supplier_verify v" + whereSql, Long.class, args.toArray()));
        out.put("pending", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM supplier_verify WHERE status = 'pending'", Long.class));
        out.put("counts", jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS total, " +
                "IFNULL(SUM(status = 'pending'),0) AS pending, " +
                "IFNULL(SUM(status = 'approved'),0) AS approved, " +
                "IFNULL(SUM(status = 'rejected'),0) AS rejected, " +
                "IFNULL(SUM(status = 'reverify'),0) AS reverify, " +
                "IFNULL(SUM(status = 'revoked'),0) AS revoked FROM supplier_verify"));

        List<Object> la = new ArrayList<>(args);
        la.add(sz);
        la.add((p - 1) * sz);
        out.put("list", jdbcTemplate.queryForList(
                "SELECT v.id, v.user_id, v.company_name, v.credit_code, v.license_url, " +
                "v.contact_name, v.contact_phone, v.status, v.remark, v.reviewed_at, " +
                "v.valid_until, v.created_at, u.username, u.nickname, u.email " +
                "FROM supplier_verify v LEFT JOIN sys_user u ON u.id = v.user_id" + whereSql +
                " ORDER BY (v.status = 'pending') DESC, v.id DESC LIMIT ? OFFSET ?", la.toArray()));
        return Result.ok(out);
    }

    /** AI 证照比对：OCR 提取执照上的公司名与信用代码，与申请信息比对（辅助人工审核，不代替人工决定） */
    @PostMapping("/supplier-verifies/{id}/ocr")
    public Result<Map<String, Object>> ocrLicense(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT company_name, credit_code, license_url FROM supplier_verify WHERE id = ?", id);
        if (rows.isEmpty()) return Result.error("申请不存在");
        String company = String.valueOf(rows.get(0).get("company_name"));
        String credit = String.valueOf(rows.get(0).get("credit_code"));
        String lic = String.valueOf(rows.get(0).get("license_url"));
        if (lic == null || lic.isBlank() || "null".equals(lic)) {
            return Result.error("该申请没有营业执照图片（早期申请未强制上传），请人工核验");
        }
        String fileName = lic.contains("/") ? lic.substring(lic.lastIndexOf('/') + 1) : lic;
        if (!fileName.matches("^license_[A-Za-z0-9_]+\\.(jpg|jpeg|png|webp)$")) {
            return Result.error("执照文件名不合法");
        }
        Path img = Paths.get("/home/ubuntu/chemprice/uploads/license", fileName);
        if (!Files.exists(img)) return Result.error("执照图片文件不存在");

        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "/home/ubuntu/chemprice/ocr_license.py", img.toString());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            boolean done = proc.waitFor(90, TimeUnit.SECONDS);
            if (!done) { proc.destroyForcibly(); return Result.error("OCR 超时，请重试"); }
            if (sb.length() == 0) return Result.error("OCR 未返回结果，请重试");

            Map<?, ?> ocr = new ObjectMapper().readValue(sb.toString(), Map.class);
            String ocrCode = ocr.get("credit_code") == null ? "" : String.valueOf(ocr.get("credit_code")).trim();
            String ocrCompany = ocr.get("company_name") == null ? "" : String.valueOf(ocr.get("company_name")).trim();

            String nCredit = credit.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            boolean codeMatch = !ocrCode.isEmpty() && nCredit.equals(ocrCode.toUpperCase(Locale.ROOT));
            String nCompany = company.replaceAll("\\s+", "");
            String nOcrCompany = ocrCompany.replaceAll("^(名称|称|名)", "").replaceAll("\\s+", "");
            boolean companyMatch = !ocrCompany.isEmpty() &&
                    (nOcrCompany.contains(nCompany) || nCompany.contains(nOcrCompany));

            String verdict;
            if (codeMatch && companyMatch) verdict = "match";
            else if (codeMatch || companyMatch) verdict = "partial";
            else if (ocrCode.isEmpty() && ocrCompany.isEmpty()) verdict = "unreadable";
            else verdict = "mismatch";

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("verdict", verdict);
            out.put("codeMatch", codeMatch);
            out.put("companyMatch", companyMatch);
            out.put("ocrCode", ocrCode);
            out.put("ocrCompany", ocrCompany);
            out.put("appCode", credit);
            out.put("appCompany", company);
            return Result.ok(out);
        } catch (Exception e) {
            return Result.error("OCR 比对失败：" + e.getMessage());
        }
    }

    /** 审核：通过 / 驳回 */
    @PostMapping("/supplier-verifies/{id}/review")
    public Result<String> reviewSupplier(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", ""));
        String remark = body.get("remark") == null ? "" : String.valueOf(body.get("remark")).trim();
        boolean approve = "approve".equals(action);
        if (!approve && !"reject".equals(action)) return Result.error("审核动作不正确");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, company_name, contact_phone, status FROM supplier_verify WHERE id = ?", id);
        if (rows.isEmpty()) return Result.error("申请不存在");
        Map<String, Object> row = rows.get(0);
        if (!"pending".equals(String.valueOf(row.get("status")))) {
            return Result.error("该申请已处理过，不能重复审核");
        }
        Long uid = ((Number) row.get("user_id")).longValue();
        String company = String.valueOf(row.get("company_name"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, auth.getName());

        // 工商登记快照（认证通过时查询企业税号接口，完整返回落库备用、摘要供名片展示；失败不影响审核）
        String regStatus = null;
        String regPayload = null;
        if (approve) {
            try {
                String appCode = System.getenv("ALIYUN_MARKET_APPCODE");
                String credit = jdbcTemplate.queryForObject(
                        "SELECT credit_code FROM supplier_verify WHERE id = ?", String.class, id);
                if (appCode != null && !appCode.isBlank() && credit != null && !credit.isBlank()) {
                    java.net.URL gsUrl = new java.net.URL("https://taxno.market.alicloudapi.com/lundear/taxno?keyword="
                            + java.net.URLEncoder.encode(credit, "UTF-8") + "&pageSize=10");
                    java.net.HttpURLConnection gconn = (java.net.HttpURLConnection) gsUrl.openConnection();
                    gconn.setRequestProperty("Authorization", "APPCODE " + appCode);
                    gconn.setConnectTimeout(8000);
                    gconn.setReadTimeout(15000);
                    StringBuilder gsb = new StringBuilder();
                    try (java.io.BufferedReader gbr = new java.io.BufferedReader(
                            new java.io.InputStreamReader(gconn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String gline;
                        while ((gline = gbr.readLine()) != null) gsb.append(gline);
                    }
                    if (gsb.length() > 0) {
                        com.fasterxml.jackson.databind.ObjectMapper gom = new com.fasterxml.jackson.databind.ObjectMapper();
                        Map<String, Object> gj = gom.readValue(gsb.toString(), Map.class);
                        if (gj.get("code") instanceof Number gn && gn.intValue() == 0) {
                            Map<String, Object> gres = (Map<String, Object>) gj.get("result");
                            List<Map<String, Object>> gitems = gres == null ? null
                                    : (List<Map<String, Object>>) gres.get("items");
                            if (gitems != null) {
                                for (Map<String, Object> it : gitems) {
                                    if (credit.equalsIgnoreCase(String.valueOf(it.get("creditCode")))) {
                                        regStatus = String.valueOf(it.get("regStatus"));
                                        regPayload = gsb.toString();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignore) { }
        }

        jdbcTemplate.update(
                "UPDATE supplier_verify SET status = ?, remark = ?, reviewed_at = NOW(), " +
                "reviewed_by = ?, valid_until = " +
                (approve ? "DATE_ADD(CURDATE(), INTERVAL 1 YEAR)" : "NULL") + ", " +
                "reg_status = ?, reg_checked_at = " + (approve ? "NOW()" : "reg_checked_at") + ", " +
                "reg_payload = ? WHERE id = ?",
                approve ? "approved" : "rejected",
                remark.isEmpty() ? null : remark, adminId, regStatus, regPayload, id);

        if (approve) {
            // 通过时一并把申请表里的联系电话写入账号（为空则补，已有则不动）
            jdbcTemplate.update(
                    "UPDATE sys_user SET supplier_status = 2, supplier_level = 1, " +
                    "supplier_company = ?, " +
                    "phone = (CASE WHEN IFNULL(phone,'') = '' THEN ? ELSE phone END) WHERE id = ?",
                    company, row.get("contact_phone"), uid);

            // 认证通过即默认开启「接收新求购消息」：仅当该账号从未手动设置过（demand_notify_at IS NULL）
            // 且存在可用邮箱时生效。用户之后手动关闭会写入 demand_notify_at，此处不再覆盖其选择。
            try {
                jdbcTemplate.update(
                        "UPDATE sys_user SET demand_notify_enabled = 1, demand_notify_at = NOW() " +
                        "WHERE id = ? AND demand_notify_at IS NULL " +
                        "AND COALESCE(NULLIF(demand_notify_email,''), NULLIF(default_contact_email,''), " +
                        "NULLIF(email,'')) IS NOT NULL",
                        uid);
            } catch (Exception ignore) { /* 默认开启失败不影响认证结果 */ }
        } else {
            jdbcTemplate.update("UPDATE sys_user SET supplier_status = 3 WHERE id = ?", uid);
        }

        // 站内通知（失败不影响审核结果）
        try {
            jdbcTemplate.update(
                    "INSERT INTO site_message (user_id, title, content, type) VALUES (?,?,?,?)",
                    uid,
                    approve ? "供应商认证已通过" : "供应商认证未通过",
                    approve ? "恭喜！你的企业认证已通过，现在可以发布供应信息了。"
                            : ("你的认证申请未通过。" + (remark.isEmpty() ? "" : "原因：" + remark)),
                    "supplier_verify");
        } catch (Exception ignore) { }

        // 结果邮件通知申请人（站内信上面已发，此处只补邮件）
        verifyNotify.onReviewed(uid, "企业认证", approve, VerifyNotifyService.fields(
                "公司名称", company,
                "审核结果", approve ? "已通过（有效期 1 年）" : "未通过",
                "审核说明", approve ? "认证已生效，可以发布供应信息了"
                        : (remark.isEmpty() ? "未填写具体原因，可联系管理员了解详情" : remark)));

        try {
            auditService.record(approve ? "SUPPLIER_APPROVE" : "SUPPLIER_REJECT", "PERMISSION",
                    "SUPPLIER", String.valueOf(id), null, null, 1, "INFO",
                    (approve ? "通过" : "驳回") + "供应商认证：" + company);
        } catch (Exception ignore) { }

        return Result.ok(approve ? "已通过认证" : "已驳回");
    }

    // ========== 供需页风险提示（文案可改） ==========

    @GetMapping("/demand-notice")
    public Result<Map<String, Object>> getDemandNotice() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", "1".equals(settingOf("demand_notice_enabled", "1")));
        out.put("title", settingOf("demand_notice_title", "交易风险提示"));
        out.put("content", settingOf("demand_notice_content", ""));
        return Result.ok(out);
    }

    @PutMapping("/demand-notice")
    public Result<String> saveDemandNotice(@RequestBody Map<String, Object> body) {
        Object en = body.get("enabled");
        boolean enabled = Boolean.TRUE.equals(en)
                || "1".equals(String.valueOf(en)) || "true".equalsIgnoreCase(String.valueOf(en));
        String title = body.get("title") == null ? "" : String.valueOf(body.get("title")).trim();
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content")).trim();

        if (title.isEmpty()) return Result.error("请填写提示标题");
        if (title.length() > 60) return Result.error("标题过长（最多 60 字）");
        if (content.length() > 2000) return Result.error("正文过长（最多 2000 字）");

        Long uid = null;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            uid = jdbcTemplate.queryForObject(
                    "SELECT id FROM sys_user WHERE username = ?", Long.class, auth.getName());
        } catch (Exception ignore) { }

        upsertSetting("demand_notice_enabled", enabled ? "1" : "0", uid);
        upsertSetting("demand_notice_title", title, uid);
        upsertSetting("demand_notice_content", content, uid);

        try {
            auditService.record("SET_DEMAND_NOTICE", "ADMIN", "SETTING", "demand_notice",
                    null, null, 1, "INFO", "修改供需页风险提示（" + (enabled ? "启用" : "停用") + "）");
        } catch (Exception ignore) { }

        return Result.ok(enabled ? "已保存并启用" : "已保存（当前为停用状态）");
    }

    private String settingOf(String key, String def) {
        List<String> r = jdbcTemplate.queryForList(
                "SELECT setting_value FROM site_setting WHERE setting_key = ?", String.class, key);
        return r.isEmpty() || r.get(0) == null ? def : r.get(0);
    }

    private void upsertSetting(String key, String val, Long uid) {
        jdbcTemplate.update(
                "INSERT INTO site_setting (setting_key, setting_value, updated_by) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), " +
                "updated_by = VALUES(updated_by)", key, val, uid);
    }

    /** 取消认证 / 要求重新认证：两者的供应信息都会同步下架 */
    @PostMapping("/suppliers/{userId}/{action}")
    public Result<String> manageSupplier(@PathVariable Long userId, @PathVariable String action,
                                         @RequestBody(required = false) Map<String, Object> body) {
        boolean revoke = "revoke".equals(action);
        boolean reverify = "require-reverify".equals(action);
        if (!revoke && !reverify) return Result.error("操作不正确");
        String remark = body == null || body.get("remark") == null
                ? "" : String.valueOf(body.get("remark")).trim();
        if (reverify && remark.isEmpty()) {
            return Result.error("要求重新认证时必须填写原因（会展示给该用户）");
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT username, nickname, IFNULL(supplier_company,'') AS company_name, " +
                "supplier_status FROM sys_user WHERE id = ?", userId);
        if (rows.isEmpty()) return Result.error("用户不存在");
        Map<String, Object> u = rows.get(0);
        Object st = u.get("supplier_status");
        if (st == null || ((Number) st).intValue() != 2) {
            return Result.error("该用户不是已认证供应商");
        }
        String company = String.valueOf(u.get("company_name"));

        int newStatus = revoke ? 0 : 3;
        jdbcTemplate.update(
                "UPDATE sys_user SET supplier_status = ?, supplier_level = 0 WHERE id = ?",
                newStatus, userId);
        // 把原因记录到其最新一条已通过的认证申请上，并同步降级状态（与账号保持一致）
        jdbcTemplate.update(
                "UPDATE supplier_verify SET remark = ?, status = ? WHERE id = (SELECT id FROM " +
                "(SELECT id FROM supplier_verify WHERE user_id = ? AND status = 'approved' " +
                " ORDER BY id DESC LIMIT 1) t)",
                (remark.isEmpty() ? (revoke ? "管理员取消认证" : "管理员要求重新认证") : remark),
                revoke ? "revoked" : "reverify", userId);
        // 其发布的供应信息全部下架
        int off = jdbcTemplate.update(
                "UPDATE demand_post SET status = 'offline' " +
                "WHERE user_id = ? AND type = 'supply' AND status = 'online'", userId);

        try {
            jdbcTemplate.update(
                    "INSERT INTO site_message (user_id, title, content, type) VALUES (?,?,?,?)",
                    userId,
                    revoke ? "供应商认证已取消" : "请重新完成供应商认证",
                    revoke
                        ? ("管理员已取消你的供应商认证" + (remark.isEmpty() ? "" : "（" + remark + "）"))
                          + "；你发布的供应信息已下架。如需继续发布请联系管理员。"
                        : ("请重新提交认证材料（" + remark + "）；你的供应信息已暂时下架，"
                           + "重新认证通过后会自动恢复展示。"),
                    "supplier_verify");
        } catch (Exception ignore) { }

        try {
            auditService.record(revoke ? "SUPPLIER_REVOKE" : "SUPPLIER_REQ_REVERIFY", "PERMISSION",
                    "SUPPLIER", String.valueOf(userId), null, null, 1, "INFO",
                    (revoke ? "取消" : "要求重新") + "认证：" + company + "，下架供应信息 " + off + " 条");
        } catch (Exception ignore) { }

        return Result.ok((revoke ? "已取消认证" : "已要求重新认证") + "，供应信息下架 " + off + " 条");
    }

    // ========== 用户管理 ==========

    @GetMapping("/users")
    public Result<List<Map<String, Object>>> getUsers() {
        // 用原生 SQL 查：否则被 logic-delete(status) 自动过滤，禁用账号在列表里根本看不到
        List<SysUser> users = userMapper.selectAllIncludingDisabled();
        List<Map<String, Object>> result = new ArrayList<>();

        // 各用户最近一次「系统活动」（取自审计日志）：一次聚合查完，避免逐用户查询
        // 用途：last_login_at 只记录输入密码的登录（JWT 7 天内免登录时不更新），
        //      管理员真正想知道的"这人最近来没来"要靠这个值。
        Map<String, Object> lastActive = new HashMap<>();
        try {
            List<Map<String, Object>> acts = jdbcTemplate.queryForList(
                "SELECT username, MAX(created_at) AS m FROM audit_log " +
                "WHERE username IS NOT NULL AND username <> '' GROUP BY username");
            for (Map<String, Object> a : acts) {
                Object un = a.get("username");
                if (un != null) lastActive.put(String.valueOf(un), a.get("m"));
            }
        } catch (Exception e) {
            // 取不到不影响主流程，前端显示为空即可
            System.err.println("查询最后活跃时间失败: " + e.getMessage());
        }
        // 供应商认证状态（SysUser 实体没有这两个新列，用 JdbcTemplate 读）
        Map<Long, Map<String, Object>> supMap = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT id, supplier_status, supplier_level, supplier_company, " +
                    "IFNULL(realname_status,0) AS realname_status, phone, " +
                    "IFNULL(realname_name,'') AS realname_name, " +
                    "IFNULL(realname_idcard,'') AS realname_idcard, " +
                    "IFNULL(realname_idcard_full,'') AS realname_idcard_full FROM sys_user " +
                    "WHERE supplier_status <> 0 OR IFNULL(realname_status,0) = 1 OR phone IS NOT NULL")) {
                supMap.put(((Number) r.get("id")).longValue(), r);
            }
            // 企业认证到期时间：取该用户最新一条已通过记录的 valid_until
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT sv.user_id, sv.valid_until FROM supplier_verify sv " +
                    "INNER JOIN (SELECT user_id, MAX(id) AS mid FROM supplier_verify " +
                    "WHERE status = 'approved' GROUP BY user_id) t ON t.mid = sv.id")) {
                Long uid = ((Number) r.get("user_id")).longValue();
                Map<String, Object> hit = supMap.get(uid);
                if (hit != null) hit.put("valid_until", r.get("valid_until"));
            }
        } catch (Exception e) {
            System.err.println("查询供应商认证状态失败: " + e.getMessage());
        }

        // 一次性查出全部用户的权限明细（原实现是每个用户一条 SQL —— 34 个用户就是 34 次，
        // 加上禁用账号后更多，页面会明显变慢）。按 user_id 分组后在循环内取用。
        Map<Long, List<Map<String, Object>>> permByUser = new HashMap<>();
        try {
            for (Map<String, Object> pr : jdbcTemplate.queryForList(
                    "SELECT dp.user_id, dp.varieties_id, dp.varieties_name, dp.expire_date, c.category "
                    + "FROM data_permission dp LEFT JOIN commodity c ON c.varieties_id = dp.varieties_id "
                    + "WHERE dp.expire_date IS NULL OR dp.expire_date >= CURDATE() "
                    + "ORDER BY c.category, dp.varieties_id")) {
                Long uid = ((Number) pr.get("user_id")).longValue();
                permByUser.computeIfAbsent(uid, k -> new ArrayList<>()).add(pr);
            }
        } catch (Exception e) {
            System.err.println("批量查询用户权限失败: " + e.getMessage());
        }

        // 企业分布图开关：一次聚合查完（SysUser 实体未映射该列）
        Map<Long, Integer> emapEnabled = new HashMap<>();
        Map<Long, String> emapExpire = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT id, IFNULL(enterprise_map_enabled,0) AS em, enterprise_map_expire_date AS ed FROM sys_user")) {
                Long uid = ((Number) r.get("id")).longValue();
                emapEnabled.put(uid, r.get("em") instanceof Number n ? n.intValue() : 0);
                Object ed = r.get("ed");
                if (ed != null) {
                    String eds = String.valueOf(ed);
                    emapExpire.put(uid, eds.length() > 10 ? eds.substring(0, 10) : eds);
                }
            }
        } catch (Exception e) {
            System.err.println("查询企业分布图开关失败: " + e.getMessage());
        }

        for (SysUser u : users) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("email", u.getEmail());
            m.put("role", u.getRole());
            m.put("status", u.getStatus());
            m.put("exportPermission", u.getExportPermission());
            m.put("createdAt", u.getCreatedAt());
            m.put("updatedAt", u.getUpdatedAt());
            m.put("lastLoginAt", u.getLastLoginAt());
            m.put("lastLoginIp", u.getLastLoginIp());
            m.put("lastActiveAt", lastActive.get(u.getUsername()));
            m.put("isTest", u.getIsTest() == null ? 0 : u.getIsTest());
            // 标点地图访问权限（用户级开关 + 有效期；本实体未映射这两列，单独聚合读取）
            int emEn = emapEnabled.getOrDefault(u.getId(), 0);
            String emEd = emapExpire.get(u.getId());
            boolean emExpired = emEd != null && emEd.compareTo(LocalDate.now().toString()) < 0;
            m.put("enterpriseMapEnabled", emEn);
            m.put("enterpriseMapExpireDate", emEd);
            // off=未开通 / on=已开通且在有效期内 / expired=已过期
            m.put("enterpriseMapStatus", emEn != 1 ? "off" : (emExpired ? "expired" : "on"));
            m.put("enterpriseMapDaysLeft",
                    emEd == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.now(), LocalDate.parse(emEd)));

            Map<String, Object> sm = supMap.get(u.getId());
            m.put("supplierStatus", sm == null ? 0
                    : (sm.get("supplier_status") instanceof Number n ? n.intValue() : 0));
            m.put("supplierLevel", sm == null ? 0
                    : (sm.get("supplier_level") instanceof Number n ? n.intValue() : 0));
            m.put("supplierCompany", sm == null ? "" : sm.get("supplier_company"));
            m.put("supplierValidUntil", sm == null ? null : sm.get("valid_until"));
            m.put("realnameStatus", sm == null ? 0
                    : (sm.get("realname_status") instanceof Number n ? n.intValue() : 0));
            m.put("phone", sm == null ? "" : (sm.get("phone") == null ? "" : String.valueOf(sm.get("phone"))));
            // 实名信息（仅管理员接口返回）：姓名 + 脱敏号 + 完整号
            m.put("realnameName", sm == null ? "" : String.valueOf(sm.getOrDefault("realname_name", "")));
            m.put("realnameIdcard", sm == null ? "" : String.valueOf(sm.getOrDefault("realname_idcard", "")));
            m.put("realnameIdcardFull", sm == null ? "" : String.valueOf(sm.getOrDefault("realname_idcard_full", "")));

            // 查询该用户的权限商品（仅未过期，关联 commodity 带出大类，避免“有标签却看不到数据”的错位）
            if ("USER".equals(u.getRole())) {
                List<Map<String, Object>> perms = permByUser.getOrDefault(u.getId(), new ArrayList<>());
                m.put("permissions", perms);
                Map<String, Integer> catCnt = new TreeMap<>();
                for (Map<String, Object> p : perms) {
                    Object cat = p.get("category");
                    String c = cat != null && !cat.toString().isBlank() ? cat.toString() : "未分类";
                    catCnt.merge(c, 1, Integer::sum);
                }
                m.put("permCategories", catCnt);
            }
            result.add(m);
        }
        return Result.ok(result);
    }

    // ========== AI 问答记录（仅管理员） ==========

    /**
     * AI 问答记录：每条问答的用户、问题、回答、命中工具、Token 用量与耗时。
     * 访问控制：本控制器在 /api/admin/** 下，SecurityConfig 已限制为 hasRole("ADMIN")。
     *
     * @param username 按用户筛选（精确）
     * @param keyword  关键词（问题或回答内容模糊匹配）
     * @param start/end 日期区间（yyyy-MM-dd）
     */
    @GetMapping("/ai-logs")
    public Result<Map<String, Object>> getAiLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String range) {

        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(100, size));

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            where.append(" AND l.username = ?");
            args.add(username.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (l.question LIKE ? OR l.answer LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        if (start != null && !start.isBlank()) {
            where.append(" AND l.created_at >= ?");
            args.add(start.trim() + " 00:00:00");
        }
        if (end != null && !end.isBlank()) {
            where.append(" AND l.created_at <= ?");
            args.add(end.trim() + " 23:59:59");
        }
        // 快捷时间窗（白名单，拼的是固定片段、不接用户输入）
        if (range != null && !range.isBlank()) {
            String win = null;
            if ("24h".equals(range)) win = "NOW() - INTERVAL 24 HOUR";
            else if ("3d".equals(range)) win = "NOW() - INTERVAL 3 DAY";
            else if ("7d".equals(range)) win = "NOW() - INTERVAL 7 DAY";
            if (win != null) {
                where.append(" AND l.created_at >= ").append(win);
            }
        }
        String whereSql = where.toString();

        Map<String, Object> out = new LinkedHashMap<>();

        // 汇总（当前筛选条件下）
        Map<String, Object> sum = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) AS calls, IFNULL(SUM(total_tokens),0) AS tokens, " +
            "IFNULL(SUM(prompt_tokens),0) AS promptTokens, IFNULL(SUM(completion_tokens),0) AS completionTokens, " +
            "IFNULL(ROUND(AVG(cost_ms)),0) AS avgCostMs, " +
            "COUNT(DISTINCT username) AS users FROM ai_chat_log l" + whereSql, args.toArray());
        out.put("summary", sum);

        // 今日汇总（不受筛选影响，作为整体概览）
        Map<String, Object> today = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) AS calls, IFNULL(SUM(total_tokens),0) AS tokens " +
            "FROM ai_chat_log WHERE created_at >= CURDATE()");
        out.put("today", today);

        // 各用户统计（供左侧/下拉筛选，附带消耗排名）
        out.put("byUser", jdbcTemplate.queryForList(
            "SELECT username, COUNT(*) AS calls, IFNULL(SUM(total_tokens),0) AS tokens " +
            "FROM ai_chat_log WHERE username IS NOT NULL AND username <> '' " +
            "GROUP BY username ORDER BY calls DESC"));

        // 总数
        Long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_chat_log l" + whereSql, Long.class, args.toArray());
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", s);

        // 列表（关联 sys_user 带出昵称）
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(s);
        listArgs.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT l.id, l.username, u.nickname, l.question, l.answer, l.tool, l.kind, l.model, " +
            "l.prompt_tokens, l.completion_tokens, l.total_tokens, l.cost_ms, l.created_at " +
            "FROM ai_chat_log l LEFT JOIN sys_user u ON u.username = l.username" + whereSql +
            " ORDER BY l.id DESC LIMIT ? OFFSET ?", listArgs.toArray());
        out.put("list", rows);

        return Result.ok(out);
    }

    @PostMapping("/users")
    public Result<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String nickname = (String) body.get("nickname");
        String email = (String) body.get("email");
        String role = (String) body.getOrDefault("role", "USER");
        Integer status = (Integer) body.getOrDefault("status", 1);
        Integer exportPermission = body.get("exportPermission") instanceof Boolean b ? (b ? 1 : 0) : (Integer) body.getOrDefault("exportPermission", 0);

        if (username == null || username.isBlank()) return Result.error("用户名不能为空");
        if (password == null || password.isBlank()) return Result.error("密码不能为空");

        // 原生 SQL 查重（忽略逻辑删除过滤，避免已删除用户名撞唯一索引）
        if (userMapper.countByUsernameRaw(username) > 0) return Result.error("用户名已存在");

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(status);
        user.setExportPermission(exportPermission);
        userMapper.insert(user);

        auditService.warn("CREATE_USER", "ADMIN", "USER", String.valueOf(user.getId()), null,
            "{\"username\":\"" + username + "\",\"role\":\"" + role + "\",\"exportPermission\":" + exportPermission + "}",
            "创建用户: " + username);

        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        return Result.ok(result);
    }

    @PutMapping("/users/{id}")
    public Result<String> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        SysUser user = userMapper.selectByIdIncludingDisabled(id);
        if (user == null) return Result.error("用户不存在");

        // 记录变更前关键字段（用于审计前后值）
        String before = "{\"role\":\"" + user.getRole() + "\",\"status\":" + user.getStatus()
            + ",\"exportPermission\":" + (user.getExportPermission() != null ? user.getExportPermission() : 0) + "}";
        Integer oldRole = user.getRole() != null && "ADMIN".equals(user.getRole()) ? 1 : 0;
        Integer oldExport = user.getExportPermission();
        Integer oldStatus = user.getStatus();

        if (body.containsKey("nickname")) user.setNickname((String) body.get("nickname"));
        if (body.containsKey("email")) user.setEmail((String) body.get("email"));
        if (body.containsKey("role")) user.setRole((String) body.get("role"));
        if (body.containsKey("exportPermission")) {
            Object ep = body.get("exportPermission");
            if (ep instanceof Boolean b) user.setExportPermission(b ? 1 : 0);
            else if (ep instanceof Number n) user.setExportPermission(n.intValue());
            else if (ep instanceof String s) user.setExportPermission("1".equals(s) || "true".equalsIgnoreCase(s) ? 1 : 0);
        }
        if (body.containsKey("status")) {
            Integer newStatus = (Integer) body.get("status");
            // 管理员不能禁用自己
            if ("ADMIN".equals(user.getRole()) && newStatus == 0) {
                return Result.error("不能禁用管理员账号");
            }
            user.setStatus(newStatus);
        }
        if (body.containsKey("password") && body.get("password") != null
            && !((String) body.get("password")).isBlank()) {
            user.setPassword(passwordEncoder.encode((String) body.get("password")));
        }
        // ⚠️ 不能用 updateById：status 被全局配置为逻辑删除字段后，MyBatis-Plus 会
        //   ① 从 SET 子句中排除 status —— 导致「禁用 / 启用」接口返回成功但实际未生效；
        //   ② 在 WHERE 追加 AND status=1 —— 导致编辑已禁用账号影响 0 行。
        //   故改用原生 SQL 显式更新。
        jdbcTemplate.update(
            "UPDATE sys_user SET username=?, password=?, nickname=?, email=?, role=?, "
            + "export_permission=?, status=? WHERE id=?",
            user.getUsername(), user.getPassword(), user.getNickname(), user.getEmail(),
            user.getRole(), user.getExportPermission(), user.getStatus(), id);

        // 管理员代改手机号（实体无 phone 列，单独更新；空串=清空绑定）
        if (body.containsKey("phone")) {
            String ph = String.valueOf(body.get("phone") == null ? "" : body.get("phone")).trim();
            if (!ph.isEmpty() && !ph.matches("^1[3-9]\\d{9}$")) {
                return Result.error("手机号格式不正确（应为 11 位大陆手机号）");
            }
            jdbcTemplate.update("UPDATE sys_user SET phone = ? WHERE id = ?",
                    ph.isEmpty() ? null : ph, id);
        }

        // 标点地图访问权限（用户级开关 + 有效期）
        if (body.containsKey("enterpriseMapEnabled") || body.containsKey("enterpriseMapExpireDate")) {
            // 先取现值：只传其中一个字段时，另一个保持原样
            Map<String, Object> cur = jdbcTemplate.queryForMap(
                "SELECT IFNULL(enterprise_map_enabled,0) AS en, enterprise_map_expire_date AS ed "
                + "FROM sys_user WHERE id = ?", id);
            int on = cur.get("en") instanceof Number n ? n.intValue() : 0;
            Object curEd = cur.get("ed");
            String expDate = curEd == null ? null : String.valueOf(curEd).substring(0, 10);

            if (body.containsKey("enterpriseMapEnabled")) {
                Object em = body.get("enterpriseMapEnabled");
                if (em instanceof Boolean b) on = b ? 1 : 0;
                else if (em instanceof Number n) on = n.intValue() != 0 ? 1 : 0;
                else if (em != null) { String ev = String.valueOf(em);
                    on = ("1".equals(ev) || "true".equalsIgnoreCase(ev)) ? 1 : 0; }
            }
            if (body.containsKey("enterpriseMapExpireDate")) {
                Object ev = body.get("enterpriseMapExpireDate");
                String ds = ev == null ? "" : String.valueOf(ev).trim();
                if (ds.isEmpty()) {
                    expDate = null;                                   // 空 = 永久
                } else {
                    if (ds.length() > 10) ds = ds.substring(0, 10);
                    LocalDate d;
                    try { d = LocalDate.parse(ds); }
                    catch (Exception e) { return Result.error("到期日格式不正确（应为 yyyy-MM-dd）"); }
                    // 只校验格式：允许过去日期（= 立即过期）。这样编辑一个「已过期」用户的
                    // 其它字段再保存时不会被拦——前端会把结果明确显示出来。
                    expDate = d.toString();
                }
            }
            if ("ADMIN".equals(user.getRole()) && on == 0) {
                return Result.error("管理员始终具备该权限，无需关闭");
            }
            // 关闭时清掉到期日，避免残留旧日期造成误读
            if (on == 0) expDate = null;

            jdbcTemplate.update(
                "UPDATE sys_user SET enterprise_map_enabled = ?, enterprise_map_expire_date = ? WHERE id = ?",
                on, expDate, id);
            String desc = on == 1
                ? ("已开通" + (expDate == null ? "（永久）" : "（有效期至 " + expDate + "）"))
                : "未开通";
            auditService.warn("SET_ENTERPRISE_MAP", "PERMISSION", "USER", String.valueOf(id),
                null, "{\"enterpriseMapEnabled\":" + on + ",\"expireDate\":"
                    + (expDate == null ? "null" : "\"" + expDate + "\"") + "}",
                "设置用户 " + user.getUsername() + " 标点地图权限: " + desc);
        }

        // 审计：导出权限变更（高价值）
        Integer newExport = user.getExportPermission();
        if (body.containsKey("exportPermission") && oldExport != null
            && !oldExport.equals(newExport)) {
            auditService.warn("SET_EXPORT_PERMISSION", "PERMISSION", "USER", String.valueOf(id),
                "{\"exportPermission\":" + oldExport + "}",
                "{\"exportPermission\":" + newExport + "}",
                "修改用户 " + user.getUsername() + " 导出权限: " + oldExport + " → " + newExport);
        }
        // 审计：角色变更
        if (body.containsKey("role") && !"ADMIN".equals(user.getRole()) && oldRole == 1) {
            // 管理员降级，不单独记录（避免噪声），交由 UPDATE_USER 汇总
        }
        // 通用更新日志
        auditService.warn("UPDATE_USER", "ADMIN", "USER", String.valueOf(id), before,
            "{\"role\":\"" + user.getRole() + "\",\"status\":" + user.getStatus()
                + ",\"exportPermission\":" + (newExport != null ? newExport : 0) + "}",
            "编辑用户: " + user.getUsername() + "（角色/状态/导出权限等）");

        return Result.ok("更新成功");
    }

    @DeleteMapping("/users/{id}")
    public Result<String> deleteUser(@PathVariable Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) return Result.error("用户不存在");
        if ("ADMIN".equals(user.getRole())) {
            Long adminCount = userMapper.selectCount(
                new QueryWrapper<SysUser>().eq("role", "ADMIN"));
            if (adminCount <= 1) return Result.error("不能删除最后一个管理员");
        }
        // 清理权限
        jdbcTemplate.update("DELETE FROM data_permission WHERE user_id = ?", id);
        userMapper.deleteById(id);

        auditService.error("DELETE_USER", "ADMIN", "USER", String.valueOf(id),
            "{\"username\":\"" + user.getUsername() + "\",\"role\":\"" + user.getRole() + "\"}", null,
            "删除用户: " + user.getUsername());

        return Result.ok("删除成功");
    }

    // ========== 产品权限管理 ==========

    @GetMapping("/users/{id}/permissions")
    public Result<List<Map<String, Object>>> getPermissions(@PathVariable Long id) {
        List<Map<String, Object>> perms = jdbcTemplate.queryForList(
            "SELECT dp.id, dp.varieties_id, dp.varieties_name, dp.expire_date, c.category " +
            "FROM data_permission dp LEFT JOIN commodity c ON c.varieties_id = dp.varieties_id " +
            "WHERE dp.user_id = ? ORDER BY c.category, dp.varieties_id", id);
        return Result.ok(perms);
    }

    @PostMapping("/users/{id}/permissions")
    public Result<String> grantPermission(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer varietiesId = (Integer) body.get("varietiesId");
        String varietiesName = (String) body.get("varietiesName");
        if (varietiesId == null) return Result.error("请选择商品");

        // 幂等授权：同一(用户,商品)已存在时仅刷新名称/到期时间，不再报“已授权”
        // ⛔ 业务规则：**数据权限永久免费**，一律不设到期日（忽略入参 expireDate）。
        //    需要限时的是「标点地图」这类单独开通的功能页权限，与数据权限是两回事。
        Object expire = null;
        jdbcTemplate.update(
            "INSERT INTO data_permission (user_id, varieties_id, varieties_name, expire_date) VALUES (?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE varieties_name = VALUES(varieties_name), expire_date = VALUES(expire_date)",
            id, varietiesId, varietiesName, expire);

        auditService.warn("GRANT_PERMISSION", "PERMISSION", "PERMISSION", null, null,
            "{\"userId\":" + id + ",\"varietiesId\":" + varietiesId + ",\"varietiesName\":\"" + varietiesName + "\"}",
            "为用户(ID:" + id + ") 授权商品: " + varietiesName + "(" + varietiesId + ")");

        return Result.ok("授权成功");
    }

    /** 按大类批量授权（该大类下全部在售商品） */
    @PostMapping("/users/{id}/permissions/category")
    public Result<String> grantCategory(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String category = (String) body.get("category");
        if (category == null || category.isBlank()) return Result.error("请选择产品大类");
        SysUser user = userMapper.selectById(id);
        if (user == null) return Result.error("用户不存在");
        if ("ADMIN".equals(user.getRole())) return Result.error("管理员默认可见全部，无需授权");

        List<Map<String, Object>> list = jdbcTemplate.queryForList(
            "SELECT varieties_id, name FROM commodity WHERE status = 1 AND category = ?", category);
        if (list.isEmpty()) return Result.error("该大类下没有在售商品");

        // ⛔ 业务规则：**数据权限永久免费**，一律不设到期日（忽略入参 expireDate）。
        Object expire = null;
        int count = 0;
        for (Map<String, Object> row : list) {
            count += jdbcTemplate.update(
                "INSERT INTO data_permission (user_id, varieties_id, varieties_name, expire_date) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE varieties_name = VALUES(varieties_name), expire_date = VALUES(expire_date)",
                id, row.get("varieties_id"), row.get("name"), expire);
        }
        auditService.warn("GRANT_PERMISSION_CATEGORY", "PERMISSION", "PERMISSION", null, null,
            "{\"userId\":" + id + ",\"category\":\"" + category + "\",\"count\":" + count + "}",
            "为用户(ID:" + id + ") 按大类授权 " + category + " 共 " + count + " 个商品");

        return Result.ok("已开通「" + category + "」大类 " + count + " 个商品权限");
    }

    /** 取消某大类的全部授权 */
    @DeleteMapping("/users/{id}/permissions/category/{category}")
    public Result<String> revokeCategory(@PathVariable Long id, @PathVariable String category) {
        if (category == null || category.isBlank()) return Result.error("请选择产品大类");
        int n = jdbcTemplate.update(
            "DELETE dp FROM data_permission dp JOIN commodity c ON c.varieties_id = dp.varieties_id " +
            "WHERE dp.user_id = ? AND c.category = ?", id, category);
        auditService.warn("REVOKE_PERMISSION_CATEGORY", "PERMISSION", "PERMISSION", null, null,
            "{\"userId\":" + id + ",\"category\":\"" + category + "\",\"count\":" + n + "}",
            "取消用户(ID:" + id + ") 大类 " + category + " 的 " + n + " 个商品授权");
        return Result.ok("已取消「" + category + "」大类 " + n + " 个商品授权");
    }

    @DeleteMapping("/users/{id}/permissions/{permId}")
    public Result<String> revokePermission(@PathVariable Long id, @PathVariable Long permId) {
        // 先查被撤销的授权信息
        List<Map<String, Object>> perm = jdbcTemplate.queryForList(
            "SELECT varieties_id, varieties_name FROM data_permission WHERE id=? AND user_id=?", permId, id);
        String revokeInfo = perm.isEmpty() ? "" :
            perm.get(0).get("varieties_name") + "(" + perm.get(0).get("varieties_id") + ")";

        jdbcTemplate.update("DELETE FROM data_permission WHERE id=? AND user_id=?", permId, id);

        auditService.warn("REVOKE_PERMISSION", "PERMISSION", "PERMISSION", String.valueOf(permId),
            "{\"userId\":" + id + ",\"varietiesName\":\"" + revokeInfo + "\"}", null,
            "取消用户(ID:" + id + ") 商品授权: " + revokeInfo);

        return Result.ok("取消授权成功");
    }

    // ========== 操作日志 ==========

    /**
     * 操作日志查询（支持筛选+分页）
     * 参数：type(大类)、userId(精确)、username(模糊)、startDate、endDate、action、page、size
     */
    /**
     * 按"业务对象"（target_type）过滤的分类。
     * AI 问答与供需广场操作在库里 operation_type 统一为 BUSINESS（混在一起），
     * 但 target_type 已分别标为 AI / DEMAND —— 直接用它做子集筛选，
     * 无需迁移历史数据、也不用改各处 record() 调用（2026-09-17）。
     */
    private static final java.util.Set<String> TARGET_TYPE_FILTERS =
            java.util.Set.of("AI", "DEMAND");

    /**
     * audit_log.user_id 历史数据 99.9% 为 NULL（1881/1883），只按 user_id 过滤**永远查不到**；
     * username 才是关联用户的权威字段。把 id 解析成 username，供「user_id 或 username」并集查询用。
     * 返回 null 表示该用户不存在（调用方退回只按 user_id 过滤）。
     */
    private String usernameOf(Long userId) {
        if (userId == null) return null;
        List<String> r = jdbcTemplate.queryForList(
                "SELECT username FROM sys_user WHERE id = ?", String.class, userId);
        return (r.isEmpty() || r.get(0) == null || r.get(0).isBlank()) ? null : r.get(0);
    }

    @GetMapping("/logs")
    public Result<Map<String, Object>> getLogs(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        QueryWrapper<AuditLog> qw = new QueryWrapper<>();
        if (type != null && !type.isBlank()) {
            // AI / DEMAND 走业务对象维度，其余走操作大类维度
            if (TARGET_TYPE_FILTERS.contains(type)) qw.eq("target_type", type);
            else qw.eq("operation_type", type);
        }
        if (userId != null) {
            // ⚠️ 不能只按 user_id 过滤：该列历史数据几乎全为 NULL（见 usernameOf 说明），
            // 否则「单个用户的日志」永远显示 0 条（2026-09-17 用户报障）
            String uname = usernameOf(userId);
            if (uname == null) {
                qw.eq("user_id", userId);
            } else {
                qw.and(w -> w.eq("user_id", userId).or().eq("username", uname));
            }
        }
        if (username != null && !username.isBlank()) qw.like("username", username);
        if (action != null && !action.isBlank()) qw.eq("action", action);
        if (startDate != null && !startDate.isBlank()) qw.ge("created_at", startDate + " 00:00:00");
        if (endDate != null && !endDate.isBlank()) qw.le("created_at", endDate + " 23:59:59");
        qw.orderByDesc("created_at");

        Long total = auditLogMapper.selectCount(qw);
        List<AuditLog> logs = auditLogMapper.selectList(qw.last("LIMIT " + (page - 1) * size + ", " + size));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", logs);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return Result.ok(result);
    }

    @GetMapping("/users/{id}/logs")
    public Result<List<AuditLog>> getUserLogs(@PathVariable Long id) {
        // 同 getLogs：user_id 列大量为 NULL，须按 username 关联（否则恒为空）
        String uname = usernameOf(id);
        QueryWrapper<AuditLog> qw = new QueryWrapper<>();
        if (uname == null) qw.eq("user_id", id);
        else qw.and(w -> w.eq("user_id", id).or().eq("username", uname));
        qw.orderByDesc("created_at").last("LIMIT 50");
        List<AuditLog> logs = auditLogMapper.selectList(qw);
        return Result.ok(logs);
    }

    // ===== 邮件推送：管理员调整用户推送产品额度（默认3，需多开由管理员调高） =====

    @GetMapping("/users/{id}/push-config")
    public Result<Map<String, Object>> getPushConfig(@PathVariable Long id) {
        // 懒初始化默认行：关、17:30、额度3
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM push_config WHERE user_id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            jdbcTemplate.update(
                "INSERT INTO push_config (user_id, enabled, push_time, push_quota, updated_at) VALUES (?, 0, '17:30', 3, NOW())",
                id);
        }
        Map<String, Object> cfg = jdbcTemplate.queryForMap(
            "SELECT enabled, push_time AS pushTime, push_quota AS pushQuota FROM push_config WHERE user_id = ?", id);
        // 当前已勾选推送数
        Integer used = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_favorite WHERE user_id = ? AND push_enabled = 1", Integer.class, id);
        cfg.put("used", used == null ? 0 : used);
        return Result.ok(cfg);
    }

    /** 重置用户的核验/上传次数（用户 3 次用完后管理员开通） */
    @PostMapping("/users/{id}/reset-verify")
    public Result<String> resetVerify(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.getOrDefault("type", ""));
        if ("realname".equals(type)) {
            jdbcTemplate.update("DELETE FROM realname_verify_log WHERE user_id = ?", id);
            auditService.record("RESET_REALNAME_TRIES", "ADMIN", "USER", String.valueOf(id),
                    null, null, 1, "INFO", "重置用户实名核验次数");
            return Result.ok("已重置实名核验次数");
        }
        if ("license".equals(type)) {
            jdbcTemplate.update(
                    "DELETE FROM audit_log WHERE user_id = ? AND action = 'UPLOAD_LICENSE'", id);
            auditService.record("RESET_LICENSE_UPLOADS", "ADMIN", "USER", String.valueOf(id),
                    null, null, 1, "INFO", "重置用户执照上传次数");
            return Result.ok("已重置执照上传次数");
        }
        return Result.error("类型不正确");
    }

    /** 解除实名：清空实名状态与全部实名字段（含完整号），用户需重新走核验流程 */
    @PostMapping("/users/{id}/reset-realname")
    public Result<String> resetRealname(@PathVariable Long id) {
        SysUser user = userMapper.selectByIdIncludingDisabled(id);
        if (user == null) return Result.error("用户不存在");
        Integer rn = jdbcTemplate.queryForObject(
                "SELECT IFNULL(realname_status,0) FROM sys_user WHERE id = ?", Integer.class, id);
        if (rn == null || rn != 1) return Result.error("该用户未实名，无需解除");
        jdbcTemplate.update(
                "UPDATE sys_user SET realname_status = 0, realname_at = NULL, " +
                "realname_name = NULL, realname_idcard = NULL, realname_idcard_full = NULL WHERE id = ?", id);
        // 顺带清空核验尝试记录：重新核验会写入新日志，旧尝试不再占用「累计 3 次」上限
        jdbcTemplate.update("DELETE FROM realname_verify_log WHERE user_id = ?", id);
        try {
            jdbcTemplate.update(
                    "INSERT INTO site_message (user_id, title, content, type) VALUES (?,?,?,?)",
                    id, "实名信息已重置",
                    "管理员已重置你的实名认证，请重新完成实名认证后再发布求购/供应信息。",
                    "realname");
        } catch (Exception ignore) { }
        auditService.warn("RESET_REALNAME", "ADMIN", "USER", String.valueOf(id),
                null, null, "解除实名并要求重新认证（实名字段与核验记录已清空）");
        return Result.ok("已解除实名，用户需重新认证");
    }

    // ===== AI 功能开关（site_setting，实时生效，无需重启）=====
    @GetMapping("/ai-flags")
    public Result<Map<String, Object>> getAiFlags() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("publish", readSettingFlag("ai_publish_enabled"));
        return Result.ok(m);
    }

    @PostMapping("/ai-flags")
    public Result<Map<String, Object>> setAiFlags(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (body.containsKey("publish")) {
            Object p = body.get("publish");
            boolean on = Boolean.TRUE.equals(p) || "1".equals(String.valueOf(p))
                    || "true".equalsIgnoreCase(String.valueOf(p));
            jdbcTemplate.update(
                    "INSERT INTO site_setting (setting_key, setting_value) VALUES ('ai_publish_enabled', ?) " +
                    "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)",
                    on ? "1" : "0");
            auditService.record("SET_AI_FLAG", "ADMIN", "SETTING", "ai_publish_enabled",
                    null, null, 1, on ? "INFO" : "WARN",
                    on ? "开启 AI 代发供需" : "关闭 AI 代发供需");
            out.put("publish", on);
        }
        return Result.ok(out);
    }

    private boolean readSettingFlag(String key) {
        try {
            String v = jdbcTemplate.queryForObject(
                    "SELECT setting_value FROM site_setting WHERE setting_key = ?", String.class, key);
            return v == null || v.isBlank() || !"0".equals(v.trim());
        } catch (Exception e) {
            return true;
        }
    }

    @PutMapping("/users/{id}/push-quota")
    public Result<String> setPushQuota(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        int quota;
        try {
            quota = Integer.parseInt(String.valueOf(body.get("pushQuota")));
        } catch (Exception e) {
            return Result.error("额度格式不正确");
        }
        if (quota < 0 || quota > 50) {
            return Result.error("额度需在 0~50 之间");
        }
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM push_config WHERE user_id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            jdbcTemplate.update(
                "INSERT INTO push_config (user_id, enabled, push_time, push_quota, updated_at) VALUES (?, 0, '17:30', ?, NOW())",
                id, quota);
        } else {
            jdbcTemplate.update(
                "UPDATE push_config SET push_quota = ?, updated_at = NOW() WHERE user_id = ?", quota, id);
        }
        auditService.warn("SET_PUSH_QUOTA", "PERMISSION", "PERMISSION", String.valueOf(id), null, null,
            "管理员将用户(ID:" + id + ") 推送产品额度设为 " + quota);
        return Result.ok("推送额度已设为 " + quota);
    }

    // ========== 用户维度运营看板（2026-09-17 新增） ==========

    /**
     * 用户看板：注册 / 登录 / 活跃 / 构成 / 参与度。
     *
     * <p>口径说明（重要）：</p>
     * <ul>
     *   <li>登录 = 输入账号密码成功（audit_log 的 LOGIN_SUCCESS）。JWT 7 天有效，
     *       持续登录态不重登不计，所以这个数会低于实际使用人数。</li>
     *   <li>活跃 = 当天有任何操作留痕，更接近"真的来过"。</li>
     *   <li><b>只统计现存账号</b>：audit_log 里残留已删除账号的历史日志（实测占 35%），
     *       不过滤会算出"活跃数 &gt; 用户总数"。</li>
     *   <li><b>默认排除测试账号</b>（sys_user.is_test=1，如「移动端测试 mobile_test」
     *       「UI自动化测试 ui_test」）：这类自动化账号操作量极大，不排除会霸占活跃榜，
     *       并把用户总数 / 活跃数一起抬高。传 includeTest=true 可查看含测试账号的口径。</li>
     * </ul>
     */
    @GetMapping("/user-stats")
    public Result<Map<String, Object>> userStats(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean includeTest) {
        int d = Math.max(7, Math.min(90, days));
        int back = d - 1;
        Map<String, Object> out = new LinkedHashMap<>();

        String T = includeTest ? "1=1" : "is_test=0";      // 裸 sys_user
        String TU = includeTest ? "1=1" : "u.is_test=0";   // 别名 u
        String TS = includeTest ? "1=1" : "su.is_test=0";  // 别名 su

        // ① 今日 / 昨日核心
        out.put("today", jdbcTemplate.queryForMap(
            "SELECT"
            + " (SELECT COUNT(*) FROM sys_user WHERE " + T + " AND DATE(created_at)=CURDATE()) AS newUsers,"
            + " (SELECT COUNT(DISTINCT a.username) FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "   WHERE " + TU + " AND a.action='LOGIN_SUCCESS' AND DATE(a.created_at)=CURDATE()) AS loginUsers,"
            + " (SELECT COUNT(*) FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "   WHERE " + TU + " AND a.action='LOGIN_SUCCESS' AND DATE(a.created_at)=CURDATE()) AS loginTimes,"
            + " (SELECT COUNT(DISTINCT a.username) FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "   WHERE " + TU + " AND DATE(a.created_at)=CURDATE()) AS activeUsers,"
            + " (SELECT COUNT(*) FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "   WHERE " + TU + " AND a.action='LOGIN_FAIL' AND DATE(a.created_at)=CURDATE()) AS loginFail,"
            + " (SELECT COUNT(*) FROM sys_user WHERE " + T
            + "   AND DATE(created_at)=DATE_SUB(CURDATE(), INTERVAL 1 DAY)) AS yNewUsers,"
            + " (SELECT COUNT(DISTINCT a.username) FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "   WHERE " + TU + " AND a.action='LOGIN_SUCCESS'"
            + "   AND DATE(a.created_at)=DATE_SUB(CURDATE(), INTERVAL 1 DAY)) AS yLoginUsers,"
            + " (SELECT COUNT(DISTINCT a.username) FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "   WHERE " + TU + " AND DATE(a.created_at)=DATE_SUB(CURDATE(), INTERVAL 1 DAY)) AS yActiveUsers"));

        // ② 累计构成
        out.put("total", jdbcTemplate.queryForMap(
            "SELECT COUNT(*) AS users,"
            + " COUNT(IF(role='ADMIN',1,NULL)) AS admins,"
            + " COUNT(IF(status=0,1,NULL)) AS disabled,"
            + " COUNT(IF(realname_status=1,1,NULL)) AS realnamed,"
            + " COUNT(IF(supplier_status=1,1,NULL)) AS supplierPending,"
            + " COUNT(IF(supplier_status=2,1,NULL)) AS suppliers,"
            + " COUNT(IF(supplier_status=3,1,NULL)) AS supplierReverify,"
            + " COUNT(IF(export_permission=1,1,NULL)) AS exporters,"
            + " COUNT(IF(phone IS NOT NULL AND phone<>'',1,NULL)) AS phoneBound,"
            + " COUNT(IF(email IS NOT NULL AND email<>'',1,NULL)) AS emailBound"
            + " FROM sys_user WHERE " + T));

        // ③ 近 N 天走势（递归日期序列，无数据的日期也占位，避免折线拉直）
        out.put("trend", jdbcTemplate.queryForList(
            "WITH RECURSIVE seq AS (SELECT DATE_SUB(CURDATE(), INTERVAL " + back + " DAY) AS d"
            + " UNION ALL SELECT DATE_ADD(d, INTERVAL 1 DAY) FROM seq WHERE d < CURDATE())"
            + " SELECT DATE_FORMAT(s.d,'%m-%d') AS d,"
            + " COALESCE(r.c,0) AS reg,"
            + " COALESCE(l.uc,0) AS loginUsers,"
            + " COALESCE(l.tc,0) AS loginTimes,"
            + " COALESCE(a.uc,0) AS activeUsers"
            + " FROM seq s"
            + " LEFT JOIN (SELECT DATE(created_at) dd, COUNT(*) c FROM sys_user"
            + "     WHERE " + T + " GROUP BY DATE(created_at)) r ON r.dd=s.d"
            + " LEFT JOIN (SELECT DATE(a.created_at) dd, COUNT(DISTINCT a.username) uc, COUNT(*) tc"
            + "     FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "     WHERE " + TU + " AND a.action='LOGIN_SUCCESS'"
            + "     GROUP BY DATE(a.created_at)) l ON l.dd=s.d"
            + " LEFT JOIN (SELECT DATE(a.created_at) dd, COUNT(DISTINCT a.username) uc"
            + "     FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "     WHERE " + TU + " GROUP BY DATE(a.created_at)) a ON a.dd=s.d"
            + " ORDER BY s.d"));

        // ④ 近 7 天活跃榜
        out.put("topActive", jdbcTemplate.queryForList(
            "SELECT a.username, COALESCE(u.nickname,a.username) AS nickname, u.role, COUNT(*) AS cnt,"
            + " COUNT(DISTINCT DATE(a.created_at)) AS days,"
            + " DATE_FORMAT(MAX(a.created_at),'%m-%d %H:%i') AS lastAt"
            + " FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + " WHERE " + TU + " AND a.created_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)"
            + " GROUP BY a.username, u.nickname, u.role ORDER BY cnt DESC LIMIT 8"));

        // ⑤ 用户参与度（每项 = 有多少个账号到达过）
        out.put("funnel", jdbcTemplate.queryForMap(
            "SELECT COUNT(*) AS total,"
            + " COUNT(IF(EXISTS(SELECT 1 FROM audit_log a WHERE a.username=su.username"
            + "   AND a.action='LOGIN_SUCCESS'),1,NULL)) AS logged,"
            + " COUNT(IF(su.phone IS NOT NULL AND su.phone<>'',1,NULL)) AS phoneBound,"
            + " COUNT(IF(su.realname_status=1,1,NULL)) AS realnamed,"
            + " COUNT(IF(su.supplier_status=2,1,NULL)) AS suppliers,"
            + " COUNT(IF(EXISTS(SELECT 1 FROM user_favorite f WHERE f.user_id=su.id),1,NULL)) AS favorited,"
            + " COUNT(IF(EXISTS(SELECT 1 FROM ai_chat_log g WHERE g.username=su.username),1,NULL)) AS aiUser,"
            + " COUNT(IF(EXISTS(SELECT 1 FROM demand_post p WHERE p.user_id=su.id),1,NULL)) AS demandUser"
            + " FROM sys_user su WHERE " + TS));

        // ⑥ 沉睡 / 流失
        out.put("sleep", jdbcTemplate.queryForMap(
            "SELECT"
            + " (SELECT COUNT(*) FROM sys_user u WHERE " + TU + " AND NOT EXISTS"
            + "   (SELECT 1 FROM audit_log a WHERE a.username=u.username"
            + "     AND a.action='LOGIN_SUCCESS')) AS neverLogin,"
            + " (SELECT COUNT(*) FROM sys_user u WHERE " + TU + " AND u.status=1 AND NOT EXISTS"
            + "   (SELECT 1 FROM audit_log a WHERE a.username=u.username"
            + "     AND a.action='LOGIN_SUCCESS')) AS neverLoginActive,"
            + " (SELECT COUNT(*) FROM sys_user WHERE " + T + " AND status=0) AS disabled,"
            + " (SELECT COUNT(DISTINCT a.username) FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "   WHERE " + TU + " AND a.created_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)) AS active7,"
            + " (SELECT COUNT(DISTINCT a.username) FROM audit_log a JOIN sys_user u ON u.username=a.username"
            + "   WHERE " + TU + " AND a.created_at >= DATE_SUB(CURDATE(), INTERVAL " + back + " DAY)) AS activeN,"
            // 排除 SYSTEM：它是系统操作日志（定时推送等），不是“已删除账号”的残留
            + " (SELECT COUNT(*) FROM audit_log a WHERE a.username IS NOT NULL"
            + "   AND a.username <> 'SYSTEM' AND NOT EXISTS"
            + "   (SELECT 1 FROM sys_user u WHERE u.username=a.username)) AS ghostRows"));

        // ⑦ 账号来源（近 N 天）：用 target_id 关联"被创建的账号"，
        //    这样能同时排除测试账号与已删除账号（CREATE_USER 的 username 是操作者，不能用来判断）
        out.put("source", jdbcTemplate.queryForMap(
            "SELECT COUNT(IF(a.action='REGISTER_SUCCESS',1,NULL)) AS selfReg,"
            + " COUNT(IF(a.action='CREATE_USER',1,NULL)) AS adminCreate"
            + " FROM audit_log a JOIN sys_user u ON u.id = CAST(a.target_id AS UNSIGNED)"
            + " WHERE a.target_type='USER' AND " + TU
            + " AND a.created_at >= DATE_SUB(CURDATE(), INTERVAL " + back + " DAY)"));

        // 测试账号总数（恒为全量，供前端提示"已排除 N 个"）
        Integer testUsers = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE is_test=1", Integer.class);
        out.put("testUsers", testUsers == null ? 0 : testUsers);
        out.put("days", d);
        out.put("includeTest", includeTest);
        return Result.ok(out);
    }
}
