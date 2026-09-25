package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.entity.SysUser;
import com.datamarket.security.PermissionHelper;
import com.datamarket.service.AuditService;
import com.datamarket.service.VerifyNotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 供应商认证：提交申请 / 查询自己的认证状态。
 * 审核为人工核验（公司全称 + 统一社会信用代码，在「国家企业信用信息公示系统」逐项比对）。
 */
@RestController
@RequestMapping("/api/supplier")
@RequiredArgsConstructor
public class SupplierController {

    private final JdbcTemplate jdbcTemplate;
    private final PermissionHelper permissionHelper;
    private final AuditService auditService;
    private final VerifyNotifyService verifyNotify;

    @Value("${chemprice.upload.dir:/home/ubuntu/chemprice/uploads}")
    private String uploadDir;

    /** 展示名：优先昵称，回退用户名 */
    private static String displayName(SysUser u) {
        String n = u == null ? null : u.getNickname();
        return (n == null || n.isBlank()) ? (u == null ? "" : String.valueOf(u.getUsername())) : n;
    }

    /** 统一社会信用代码：18 位（排除 I O S V Z） */
    private static final java.util.regex.Pattern USCC =
            java.util.regex.Pattern.compile("^[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}$");
    private static final java.util.regex.Pattern PHONE =
            java.util.regex.Pattern.compile("^1[3-9]\\d{9}$");

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 我的认证状态 */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null) return Result.error(401, "未登录");

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> su;
        try {
            su = jdbcTemplate.queryForMap(
                    "SELECT supplier_status, supplier_level, supplier_company FROM sys_user WHERE id = ?",
                    u.getId());
        } catch (Exception e) {
            su = new LinkedHashMap<>();
            su.put("supplier_status", 0);
            su.put("supplier_level", 0);
            su.put("supplier_company", null);
        }
        out.putAll(su);

        List<Map<String, Object>> apps = jdbcTemplate.queryForList(
                "SELECT id, company_name, credit_code, license_url, contact_name, contact_phone, " +
                "status, remark, valid_until, created_at, reviewed_at " +
                "FROM supplier_verify WHERE user_id = ? ORDER BY id DESC LIMIT 1", u.getId());
        out.put("apply", apps.isEmpty() ? null : apps.get(0));
        return Result.ok(out);
    }

    /** 提交（或重新提交）认证申请 */
    @PostMapping("/apply")
    public Result<Map<String, Object>> apply(@RequestBody Map<String, Object> body) {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null) return Result.error(401, "未登录");

        if (!"1".equals(settingVal("supply_enabled", "1")) && !"ADMIN".equals(u.getRole())) {
            return Result.error(403, "供需对接功能暂未开放，认证申请暂停受理");
        }
        // 企业认证前置：先完成实名认证（发布供应货源需实名 + 绑定手机号 + 企业认证）
        List<Integer> rns = jdbcTemplate.queryForList(
                "SELECT IFNULL(realname_status,0) FROM sys_user WHERE id = ?", Integer.class, u.getId());
        if (rns.isEmpty() || rns.get(0) != 1) {
            return Result.error(403, "企业认证前请先完成实名认证（免费，见认证页顶部）");
        }
        String company = str(body.get("companyName"));
        String credit = str(body.get("creditCode")).toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        String contact = str(body.get("contactName"));
        String phone = str(body.get("contactPhone")).replaceAll("[^0-9]", "");
        String license = str(body.get("licenseUrl"));

        if (license.isEmpty()) return Result.error("请上传营业执照图片");
        // 归一化：兼容「license/xxx.png」与纯文件名，只保留文件名再校验
        String licName = license.contains("/")
                ? license.substring(license.lastIndexOf('/') + 1) : license;
        if (licName.length() > 120
                || !licName.matches("^license_[A-Za-z0-9_]+\\.(jpg|jpeg|png|webp)$")) {
            return Result.error("营业执照图片不合法，请重新上传");
        }
        license = licName;

        // 工商核验：按信用代码查阿里云市场（cmapi00059970），校验公司存在性与登记状态。
        // 接口异常/超时不阻塞提交，交管理员人工核验。
        try {
            String appCode = System.getenv("ALIYUN_MARKET_APPCODE");
            if (appCode != null && !appCode.isBlank()) {
                String gs;
                {
                    java.net.URL url = new java.net.URL("https://taxno.market.alicloudapi.com/lundear/taxno?keyword="
                            + java.net.URLEncoder.encode(credit, "UTF-8") + "&pageSize=10");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("Authorization", "APPCODE " + appCode);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(15000);
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    gs = sb.toString();
                }
                if (gs != null) {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String, Object> gj = om.readValue(gs, Map.class);
                    Object codeObj = gj.get("code");
                    int gCode = codeObj instanceof Number n ? n.intValue() : -1;
                    if (gCode == 2) {
                        return Result.error("工商系统中未查询到该公司，请核对统一社会信用代码后重新提交");
                    }
                    if (gCode == 0) {
                        Map<String, Object> res = (Map<String, Object>) gj.get("result");
                        List<Map<String, Object>> items = res == null ? null
                                : (List<Map<String, Object>>) res.get("items");
                        Map<String, Object> hit = null;
                        if (items != null) {
                            for (Map<String, Object> it : items) {
                                if (credit.equalsIgnoreCase(String.valueOf(it.get("creditCode")))) { hit = it; break; }
                            }
                        }
                        if (hit == null) {
                            return Result.error("工商系统中未查询到该公司，请核对统一社会信用代码后重新提交");
                        }
                        String regStatus = String.valueOf(hit.get("regStatus"));
                        if (!(regStatus.contains("存续") || regStatus.contains("在业") || regStatus.contains("正常"))) {
                            return Result.error("该公司当前登记状态为「" + regStatus + "」，无法通过认证");
                        }
                    }
                }
            }
        } catch (Exception ignore) { }

        // 营业执照有效期校验：OCR 提取营业期限，已过期则拒绝（提取不到交管理员人工核验）
        try {
            java.nio.file.Path licPath = java.nio.file.Paths.get(uploadDir, "license", license);
            if (java.nio.file.Files.exists(licPath)) {
                Map<String, Object> lo = runLicenseOcr(licPath.toFile().getAbsolutePath());
                if (lo != null && Boolean.TRUE.equals(lo.get("expired"))) {
                    return Result.error("该营业执照已过期（营业期限至 "
                            + String.valueOf(lo.get("expire_date")) + "），请上传有效执照");
                }
            }
        } catch (Exception ignore) { }

        if (company.length() < 4) return Result.error("请填写完整的公司全称（不少于 4 个字）");
        if (company.length() > 128) return Result.error("公司全称过长");
        if (!USCC.matcher(credit).matches()) {
            return Result.error("统一社会信用代码格式不正确（应为 18 位，不含 I、O、S、V、Z）");
        }
        if (contact.isEmpty()) return Result.error("请填写联系人姓名");
        if (!PHONE.matcher(phone).matches()) return Result.error("请填写正确的 11 位手机号");

        // 已有申请：待审 / 已通过的不能再提
        // 注意：以账号当前的认证状态为准——被取消认证(0)/要求重新认证(3)的用户必须允许重新申请
        Integer curStatus = null;
        try {
            List<Integer> cs = jdbcTemplate.queryForList(
                    "SELECT supplier_status FROM sys_user WHERE id = ?", Integer.class, u.getId());
            curStatus = cs.isEmpty() ? null : cs.get(0);
        } catch (Exception ignore) { }
        List<Map<String, Object>> cur = jdbcTemplate.queryForList(
                "SELECT status FROM supplier_verify WHERE user_id = ? ORDER BY id DESC LIMIT 1", u.getId());
        if (!cur.isEmpty()) {
            String st = str(cur.get(0).get("status"));
            if ("pending".equals(st) && curStatus != null && curStatus == 1) {
                return Result.error("你已提交申请，正在审核中，请耐心等待结果");
            }
            if ("approved".equals(st) && curStatus != null && curStatus == 2) {
                return Result.error("你已是认证供应商，无需重复提交");
            }
        }

        // 同一家公司只能被一个账号认证
        Long exist = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM supplier_verify WHERE credit_code = ? AND status = 'approved' AND user_id <> ?",
                Long.class, credit, u.getId());
        if (exist != null && exist > 0) {
            return Result.error("该公司名称/信用代码已被其他账号认证，如有疑问请联系管理员");
        }

        jdbcTemplate.update(
                "INSERT INTO supplier_verify (user_id, company_name, credit_code, license_url, " +
                "contact_name, contact_phone) VALUES (?,?,?,?,?,?)",
                u.getId(), company, credit, license.isEmpty() ? null : license, contact, phone);
        jdbcTemplate.update("UPDATE sys_user SET supplier_status = 1 WHERE id = ?", u.getId());

        try {
            auditService.record("SUPPLIER_APPLY", "BUSINESS", "SUPPLIER", String.valueOf(u.getId()),
                    null, null, 1, "INFO", "提交供应商认证申请：" + company);
        } catch (Exception ignore) { /* 审计失败不影响主流程 */ }

        // 通知管理员（站内信 + 邮件）——整体吞异常，通知失败不影响申请已受理的事实
        verifyNotify.onNewApply("企业认证", VerifyNotifyService.fields(
                "公司名称", company,
                "统一社会信用代码", credit,
                "联系人", contact,
                "联系电话", phone,
                "申请人", displayName(u)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "pending");
        return Result.ok(out);
    }
    /** OCR 读取执照营业期限（复用 ocr_license.py；失败返回 null，不阻塞提交） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runLicenseOcr(String imgPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "/home/ubuntu/chemprice/ocr_license.py", imgPath);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            if (!proc.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) { proc.destroyForcibly(); return null; }
            if (sb.length() == 0) return null;
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(sb.toString(), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String settingVal(String key, String def) {
        List<String> r = jdbcTemplate.queryForList(
                "SELECT setting_value FROM site_setting WHERE setting_key = ?", String.class, key);
        return r.isEmpty() || r.get(0) == null ? def : r.get(0);
    }
    /* ================= 求购邮件通知设置（仅企业认证用户可用） ================= */

    /** 是否企业认证通过 */
    private boolean isSupplierApproved(Long userId) {
        try {
            java.util.List<String> st = jdbcTemplate.queryForList(
                    "SELECT status FROM supplier_verify WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, userId);
            return !st.isEmpty() && "approved".equalsIgnoreCase(st.get(0));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validEmail(String s) {
        return s != null && s.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    /** 读取我的求购通知设置 */
    @GetMapping("/demand-notify")
    public Result<Map<String, Object>> getDemandNotify() {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null) return Result.error(401, "未登录");

        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                    "SELECT IFNULL(demand_notify_enabled,0) AS en, IFNULL(demand_notify_email,'') AS nemail, " +
                    "IFNULL(default_contact_email,'') AS cemail, IFNULL(email,'') AS aemail FROM sys_user WHERE id = ?",
                    u.getId());
        } catch (Exception e) {
            row = new java.util.HashMap<>();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        boolean verified = isSupplierApproved(u.getId());
        boolean admin = "ADMIN".equals(u.getRole());
        Object en = row.get("en");
        out.put("verified", verified);
        // eligible = 能真正开启通知的身份：认证企业，或平台管理员（便于自测）
        out.put("eligible", verified || admin);
        out.put("enabled", en instanceof Number && ((Number) en).intValue() == 1);
        String mail = str(row.get("nemail"));
        if (mail.isEmpty()) mail = str(row.get("cemail"));
        if (mail.isEmpty()) mail = str(row.get("aemail"));
        out.put("email", mail);
        out.put("accountEmail", str(row.get("aemail")));
        return Result.ok(out);
    }

    /** 保存求购通知设置（仅企业认证用户） */
    @PutMapping("/demand-notify")
    public Result<Map<String, Object>> saveDemandNotify(@RequestBody Map<String, Object> body) {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null) return Result.error(401, "未登录");
        if (!isSupplierApproved(u.getId()) && !"ADMIN".equals(u.getRole())) {
            return Result.error(403, "仅企业认证用户在认证通过后可开启求购通知");
        }

        String enStr = str(body.get("enabled"));
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"))
                || "1".equals(enStr) || "true".equalsIgnoreCase(enStr);
        String email = str(body.get("email"));
        if (enabled && !validEmail(email)) return Result.error(400, "请填写有效的接收邮箱");

        jdbcTemplate.update(
                "UPDATE sys_user SET demand_notify_enabled = ?, demand_notify_email = ?, demand_notify_at = NOW() WHERE id = ?",
                enabled ? 1 : 0, email.isEmpty() ? null : email, u.getId());

        try {
            auditService.record("DEMAND_NOTIFY_SETTING", "BUSINESS", "SUPPLIER", String.valueOf(u.getId()),
                    null, null, 1, "INFO",
                    enabled ? ("开启求购邮件通知 -> " + email) : "关闭求购邮件通知");
        } catch (Exception ignore) { /* 审计失败不影响主流程 */ }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", enabled);
        out.put("email", email);
        return Result.ok(out);
    }

}
