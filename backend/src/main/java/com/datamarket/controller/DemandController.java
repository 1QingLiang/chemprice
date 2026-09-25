package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.entity.SysUser;
import com.datamarket.security.PermissionHelper;
import com.datamarket.service.DemandService;
import com.datamarket.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 供需对接：用户发需求（demand）、认证供应商发供给（supply）。
 *
 * 隐私口径（重要，必须服务端强制，不能只靠前端隐藏）：
 *  - 列表 / 详情接口里，联系方式一律返回**打码值**，真实值绝不下发；
 *  - 只有 ADMIN 或已认证供应商（sys_user.supplier_status=2）调用 /contact 才拿到真实值，
 *    并且每次查看都写入 contact_view_log 做留痕 + 每日限频（防「认证一次批量导客户名单」）。
 */
@RestController
@RequestMapping("/api/demand")
@RequiredArgsConstructor
public class DemandController {

    private final JdbcTemplate jdbcTemplate;
    private final PermissionHelper permissionHelper;
    private final AuditService auditService;
    private final DemandService demandService;

    /** 已认证供应商每日可查看的联系方式条数上限 */
    private static final int DAILY_VIEW_LIMIT = 50;
    /** 每人每日发布上限 */
    private static final int DAILY_POST_LIMIT = 5;
    /** 供需信息有效期（天） */
    private static final int EXPIRE_DAYS = 30;

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // ---------------- 权限 & 打码 ----------------

    private Integer supplierStatusOf(Long userId) {
        return demandService.supplierStatusOf(userId);
    }

    /** 当前用户能否看完整联系方式：管理员 or 已认证供应商 */
    private boolean canViewFull(SysUser u) {
        if (u == null) return false;
        if ("ADMIN".equals(u.getRole())) return true;
        return supplierStatusOf(u.getId()) == 2;
    }

    /** 该用户是否已通过实名认证 */
    private boolean isRealnameVerified(Long userId) {
        if (userId == null) return false;
        try {
            List<Integer> r = jdbcTemplate.queryForList(
                    "SELECT IFNULL(realname_status,0) FROM sys_user WHERE id = ?", Integer.class, userId);
            return !r.isEmpty() && r.get(0) != null && r.get(0) == 1;
        } catch (Exception e) { return false; }
    }

    /** 该帖子是否属于「企业信息」（发布者已通过企业认证） */
    private boolean isEnterprisePoster(Long posterId) {
        return posterId != null && supplierStatusOf(posterId) == 2;
    }

    /**
     * 能否查看这条帖子的完整联系方式（2026-09-16 口径）：
     *   管理员 / 发布者本人 / 已认证企业  → 可看全部；
     *   已实名（但未认证企业）            → 只能看「企业发布的」帖子；
     *   未实名                            → 不可看。
     */
    private boolean canSeeContact(SysUser u, Long posterId, boolean owner) {
        if (u == null) return false;
        if (owner) return true;
        if ("ADMIN".equals(u.getRole())) return true;
        if (supplierStatusOf(u.getId()) == 2) return true;      // 已认证企业：维持原有全量可见
        return isRealnameVerified(u.getId()) && isEnterprisePoster(posterId);
    }

    static String maskPhone(String p) { return DemandService.maskPhone(p); }

    static String maskEmail(String e) { return DemandService.maskEmail(e); }

    /** 把一行记录里的联系方式替换为打码值 */
    private void maskRow(Map<String, Object> row) {
        row.put("contact_phone", maskPhone(str(row.get("contact_phone"))));
        row.put("contact_email", maskEmail(str(row.get("contact_email"))));
        // 2026-09-21 需求：公司名称不在列表/详情里对外展示，
        // 只有「查看完整联系方式」（实名/认证，留痕+限频）后才可见。
        row.put("company_name", null);
        row.put("supplier_company", null);
    }

    private SysUser me() { return permissionHelper.getCurrentUser(); }

    // ---------------- 模块总开关 ----------------

    private static final String MSG_SUPPLY_OFF = "供需对接功能暂未开放，请稍后再来";

    private boolean supplyOff() { return !"1".equals(setting("supply_enabled", "1")); }
    private boolean isAdminUser(SysUser u) { return u != null && "ADMIN".equals(u.getRole()); }
    private boolean supplyBlocked(SysUser u) { return supplyOff() && !isAdminUser(u); }

    // ---------------- 列表 ----------------

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(required = false) Integer varietiesId,
            @RequestParam(required = false) String varietiesNames,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        if (supplyBlocked(me())) return Result.error(403, MSG_SUPPLY_OFF);

        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(50, size));

        StringBuilder where = new StringBuilder(
                " WHERE d.status = 'online' AND (d.expire_at IS NULL OR d.expire_at >= NOW())");
        List<Object> args = new ArrayList<>();

        if ("demand".equals(type) || "supply".equals(type)) {
            where.append(" AND d.type = ?");
            args.add(type);
        }
        // 多选产品：按名称 LIKE 匹配（一条信息可能含多个产品，用「、」连接）
        if (varietiesNames != null && !varietiesNames.isBlank()) {
            List<String> names = new ArrayList<>();
            for (String t : varietiesNames.split(",")) {
                String v = t.trim();
                if (!v.isEmpty() && names.size() < 20) names.add(v);
            }
            if (!names.isEmpty()) {
                where.append(" AND (");
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) where.append(" OR ");
                    where.append("d.varieties_name LIKE ?");
                    args.add("%" + names.get(i) + "%");
                }
                where.append(")");
            }
        } else if (varietiesId != null) {
            where.append(" AND d.varieties_id = ?");
            args.add(varietiesId);
        }
        if (region != null && !region.isBlank()) {
            where.append(" AND d.region = ?");
            args.add(region.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            // 搜索范围：标题 / 产品 / 详细说明 / 发布方填的公司名 / 认证公司名
            where.append(" AND (d.title LIKE ? OR d.varieties_name LIKE ? OR d.remark LIKE ? " +
                         "OR d.company_name LIKE ? OR u.supplier_company LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw); args.add(kw); args.add(kw); args.add(kw); args.add(kw);
        }
        String whereSql = where.toString();

        Map<String, Object> out = new LinkedHashMap<>();
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demand_post d JOIN sys_user u ON u.id = d.user_id" + whereSql,
                Long.class, args.toArray());
        out.put("total", total == null ? 0L : total);

        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(s);
        listArgs.add((p - 1) * s);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT d.id, d.type, d.title, d.varieties_id, d.varieties_name, d.quantity, d.unit, " +
                "d.spec, d.expect_price, d.delivery_date, d.region, d.remark, d.company_name, " +
                "d.contact_name, d.contact_phone, d.contact_email, d.expire_at, d.view_count, " +
                "d.contact_view_count, d.created_at, d.user_id, u.nickname, u.username, " +
                "u.supplier_status, u.supplier_level, u.supplier_company " +
                "FROM demand_post d JOIN sys_user u ON u.id = d.user_id" + whereSql +
                " ORDER BY d.id DESC LIMIT ? OFFSET ?", listArgs.toArray());

        SysUser u = me();
        for (Map<String, Object> r : rows) {
            maskRow(r);
            // 前端据此判断"是否本人发布"（决定是否显示编辑/管理入口）；
            // 算完即移除 user_id —— 不把用户主键下发给无关的人。
            r.put("mine", u != null && Objects.equals(u.getId(), toLong(r.get("user_id"))));
            // 是否「企业信息」（发布者已企业认证）——供前端提示与筛选
            Object sst = r.get("supplier_status");
            r.put("enterprise", sst instanceof Number n && n.intValue() == 2);
            r.remove("user_id");
        }

        out.put("list", rows);
        out.put("canViewFullContact", canViewFull(u));
        out.put("page", p);
        out.put("size", s);
        return Result.ok(out);
    }

    /** 筛选下拉选项 */
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        if (supplyBlocked(me())) return Result.error(403, MSG_SUPPLY_OFF);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commodities", jdbcTemplate.queryForList(
                "SELECT varieties_id AS id, name, IFNULL(NULLIF(category,''),'其他') AS category " +
                "FROM commodity ORDER BY category, name"));
        out.put("regions", jdbcTemplate.queryForList(
                "SELECT DISTINCT region FROM demand_post WHERE region IS NOT NULL AND region <> '' " +
                "ORDER BY region"));
        return Result.ok(out);
    }

    // ---------------- 详情 ----------------

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        if (supplyBlocked(me())) return Result.error(403, MSG_SUPPLY_OFF);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT d.*, u.nickname, u.username, u.supplier_status, u.supplier_level, " +
                "u.supplier_company FROM demand_post d JOIN sys_user u ON u.id = d.user_id " +
                "WHERE d.id = ?", id);
        if (rows.isEmpty()) return Result.error("该供需信息不存在或已下架");

        Map<String, Object> row = rows.get(0);
        SysUser u = me();
        // 浏览量 +1（不看自己的）
        if (u == null || !Objects.equals(u.getId(), toLong(row.get("user_id")))) {
            jdbcTemplate.update("UPDATE demand_post SET view_count = view_count + 1 WHERE id = ?", id);
            row.put("view_count", toLong(row.get("view_count")) + 1);
        }
        boolean mine = u != null && Objects.equals(u.getId(), toLong(row.get("user_id")));
        // 本人/管理员查看自己的帖不脱敏：否则「编辑」回填时手机号变成 139****1111，保存必然被校验拦住
        if (!mine && !isAdminUser(u)) maskRow(row);
        row.put("items", parseItems(row.get("items")));
        Map<String, Object> out = new LinkedHashMap<>();
        Long posterId = toLong(row.get("user_id"));
        out.put("post", row);
        out.put("canViewFullContact", canViewFull(u));
        // 逐帖判定：实名用户对「企业帖」可见、对「个人帖」不可见，前端据此显示按钮/提示
        out.put("canViewContact", canSeeContact(u, posterId, mine));
        out.put("enterprisePost", isEnterprisePoster(posterId));
        out.put("mine", mine);
        return Result.ok(out);
    }

    /** 把 demand_post.items 的 JSON 字符串转成数组返回给前端；老数据（无 items）兜底成单行 */
    private static java.util.List<Map<String, Object>> parseItems(Object raw) {
        String js = raw == null ? "" : String.valueOf(raw).trim();
        if (js.isEmpty() || "null".equals(js)) return null;
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(js, Object.class);
            if (parsed instanceof java.util.List<?> l && !l.isEmpty()) {
                java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mm = (Map<String, Object>) m;
                        out.add(mm);
                    }
                }
                return out.isEmpty() ? null : out;
            }
        } catch (Exception ignore) { }
        return null;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    // ---------------- 查看完整联系方式（限频 + 留痕） ----------------

    /** 企业名片：认证信息 + 工商登记状态快照 + 联系人（按权限脱敏） */
    @GetMapping("/{id}/card")
    public Result<Map<String, Object>> card(@PathVariable Long id) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (supplyBlocked(u)) return Result.error(403, MSG_SUPPLY_OFF);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT d.user_id, d.company_name AS d_company, d.contact_name, d.contact_phone, d.contact_email, " +
                "v.company_name AS v_company, v.credit_code, v.reg_status, v.reg_checked_at, v.valid_until " +
                "FROM demand_post d LEFT JOIN supplier_verify v ON v.user_id = d.user_id AND v.status = 'approved' " +
                "WHERE d.id = ? ORDER BY v.id DESC LIMIT 1", id);
        if (rows.isEmpty()) return Result.error("该供需信息不存在");
        Map<String, Object> row = rows.get(0);
        boolean owner = Objects.equals(u.getId(), toLong(row.get("user_id")));
        boolean admin = "ADMIN".equals(u.getRole());
        boolean full = canSeeContact(u, toLong(row.get("user_id")), owner);

        Object vCompany = row.get("v_company");
        boolean verified = vCompany != null && !String.valueOf(vCompany).isBlank();
        String phone = row.get("contact_phone") == null ? "" : String.valueOf(row.get("contact_phone"));
        String email = row.get("contact_email") == null ? "" : String.valueOf(row.get("contact_email"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verified", verified);
        // 公司全称 / 统一社会信用代码：仅「可查看完整联系方式」的用户（实名或已认证企业/本人/管理员）可见
        out.put("companyName", full ? (verified ? vCompany : row.get("d_company")) : null);
        out.put("regStatus", row.get("reg_status"));
        out.put("regCheckedAt", row.get("reg_checked_at") == null ? null
                : String.valueOf(row.get("reg_checked_at")).substring(0, 10));
        out.put("validUntil", row.get("valid_until") == null ? null
                : String.valueOf(row.get("valid_until")).substring(0, 10));
        out.put("creditCode", full ? row.get("credit_code") : null);
        out.put("contactName", row.get("contact_name"));
        out.put("contactPhone", full ? phone : maskPhone(phone));
        out.put("contactEmail", full ? email : maskEmail(email));
        out.put("full", full);
        out.put("companyHidden", !full);
        return Result.ok(out);
    }

    @PostMapping("/{id}/contact")
    public Result<Map<String, Object>> contact(@PathVariable Long id) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (supplyBlocked(u)) return Result.error(403, MSG_SUPPLY_OFF);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, contact_name, contact_phone, contact_email, company_name " +
                "FROM demand_post WHERE id = ?", id);
        if (rows.isEmpty()) return Result.error("该供需信息不存在");

        Map<String, Object> row = rows.get(0);
        boolean owner = Objects.equals(u.getId(), toLong(row.get("user_id")));
        boolean admin = "ADMIN".equals(u.getRole());

        Long posterId = toLong(row.get("user_id"));
        if (!canSeeContact(u, posterId, owner)) {
            // 拒绝原因分两种，给用户明确的下一步指引
            return Result.error(403, isEnterprisePoster(posterId)
                    ? "查看企业联系方式需先完成实名认证（免费），请到「供应商认证」页办理"
                    : "该信息由个人发布，完整联系方式不对外开放");
        }

        if (!owner && !admin) {
            Long used = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM contact_view_log WHERE viewer_id = ? AND created_at >= CURDATE()",
                    Long.class, u.getId());
            long n = used == null ? 0 : used;
            if (n >= DAILY_VIEW_LIMIT) {
                return Result.error("今日查看联系方式已达上限（" + DAILY_VIEW_LIMIT + " 条），请明天再试");
            }
            jdbcTemplate.update(
                    "INSERT INTO contact_view_log (viewer_id, post_id) VALUES (?,?)", u.getId(), id);
            jdbcTemplate.update(
                    "UPDATE demand_post SET contact_view_count = contact_view_count + 1 WHERE id = ?", id);
            try {
                auditService.record("VIEW_DEMAND_CONTACT", "BUSINESS", "DEMAND", String.valueOf(id),
                        null, null, 1, "INFO", "查看供需联系方式 #" + id
                        + "，今日已用 " + (n + 1) + "/" + DAILY_VIEW_LIMIT);
            } catch (Exception ignore) { }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contactName", row.get("contact_name"));
        out.put("contactPhone", row.get("contact_phone"));
        out.put("contactEmail", row.get("contact_email"));
        out.put("companyName", row.get("company_name"));
        if (!owner && !admin) {
            Long used = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM contact_view_log WHERE viewer_id = ? AND created_at >= CURDATE()",
                    Long.class, u.getId());
            out.put("usedToday", used == null ? 0L : used);
            out.put("dailyLimit", DAILY_VIEW_LIMIT);
        }
        return Result.ok(out);
    }

    // ---------------- 发布 ----------------

    @PostMapping
    public Result<Map<String, Object>> publish(@RequestBody Map<String, Object> body) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        // 规则（认证校验 / 每日限频 / 有效期）统一在 DemandService，AI 智能问价也走同一入口
        return demandService.publish(u, body);
    }

    // ---------------- 我的发布 / 下架 ----------------

    @GetMapping("/mine")
    public Result<List<Map<String, Object>>> mine() {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (supplyBlocked(u)) return Result.error(403, MSG_SUPPLY_OFF);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, type, title, varieties_id, varieties_name, quantity, unit, spec, expect_price, " +
                "items, delivery_date, region, remark, company_name, contact_name, contact_phone, " +
                "contact_email, status, view_count, contact_view_count, expire_at, created_at " +
                "FROM demand_post WHERE user_id = ? ORDER BY id DESC LIMIT 100", u.getId());
        // items 是 JSON 字符串：这里转成数组再返回，否则前端「编辑」拿不到多行明细（只剩第一行）
        for (Map<String, Object> row : rows) {
            row.put("items", parseItems(row.get("items")));
        }
        return Result.ok(rows);
    }

    // ---------------- 页首风险提示（文案由管理员在后台维护） ----------------

    @GetMapping("/notice")
    public Result<Map<String, Object>> notice() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", "1".equals(setting("demand_notice_enabled", "1")));
        out.put("title", setting("demand_notice_title", "交易风险提示"));
        out.put("content", setting("demand_notice_content", ""));
        return Result.ok(out);
    }

    // ---------------- 模块总开关（管理员控制） ----------------

    /** 模块开关状态：登录用户可读（前端用于隐藏菜单/入口） */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        SysUser u = me();
        out.put("enabled", !supplyOff());
        out.put("admin", isAdminUser(u));
        boolean realname = false;
        if (u != null) {
            try {
                Map<String, Object> r = jdbcTemplate.queryForMap(
                        "SELECT IFNULL(realname_status,0) AS rn FROM sys_user WHERE id = ?", u.getId());
                realname = r.get("rn") instanceof Number n && n.intValue() == 1;
            } catch (Exception ignore) { }
        }
        out.put("realname", realname);
        // 企业认证状态（supplierStatusOf 已含有效期兜底：过期返回 3=需重新提交）
        int supSt = u == null ? 0 : demandService.supplierStatusOf(u.getId());
        out.put("supplierStatus", supSt);
        out.put("supplierExpired", supSt == 3 && u != null && demandService.isSupplierExpired(u.getId()));
        // 已保存的默认联系方式（用户上次发布时实际填写的），前端发布表单/AI 代发优先带出
        if (u != null) {
            String[] dc = demandService.defaultContactOf(u.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", dc[0]);
            m.put("phone", dc[1]);
            m.put("email", dc[2]);
            out.put("defaultContact", m);
        }
        return Result.ok(out);
    }

    /** 管理员开启/关闭整个供需对接模块 */
    @PutMapping("/enabled")
    public Result<Map<String, Object>> setEnabled(@RequestBody Map<String, Object> body) {
        SysUser u = me();
        if (!isAdminUser(u)) return Result.error(403, "仅管理员可操作");
        boolean on = Boolean.TRUE.equals(body.get("enabled"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("enabled")))
                || "1".equals(String.valueOf(body.get("enabled")));
        jdbcTemplate.update(
                "INSERT INTO site_setting(setting_key, setting_value, updated_at) " +
                "VALUES('supply_enabled', ?, NOW()) " +
                "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()",
                on ? "1" : "0");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", on);
        return Result.ok(out);
    }

    /** 危化品名单读取（管理员） */
    @GetMapping("/dangerous-chem")
    public Result<Map<String, Object>> getDangerous() {
        if (!isAdminUser(me())) return Result.error(403, "仅管理员可操作");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keywords", demandService.dangerousKeywords());
        out.put("isDefault", setting("dangerous_chem_keywords", "").trim().isEmpty());
        return Result.ok(out);
    }

    /** 危化品名单保存（管理员；清空 = 恢复内置默认名单） */
    @PutMapping("/dangerous-chem")
    public Result<Map<String, Object>> setDangerous(@RequestBody Map<String, Object> body) {
        SysUser u = me();
        if (!isAdminUser(u)) return Result.error(403, "仅管理员可操作");
        String kw = body.get("keywords") == null ? "" : String.valueOf(body.get("keywords")).trim();
        if (kw.length() > 4000) return Result.error("名单过长（最多 4000 字）");
        jdbcTemplate.update(
                "INSERT INTO site_setting(setting_key, setting_value, updated_at) " +
                "VALUES('dangerous_chem_keywords', ?, NOW()) " +
                "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()", kw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keywords", demandService.dangerousKeywords());
        out.put("isDefault", kw.isEmpty());
        return Result.ok(out);
    }

    private String setting(String key, String def) {
        List<String> r = jdbcTemplate.queryForList(
                "SELECT setting_value FROM site_setting WHERE setting_key = ?", String.class, key);
        return r.isEmpty() || r.get(0) == null ? def : r.get(0);
    }

    /** 修改自己发布的供需信息 */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @RequestBody Map<String, Object> body) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        return demandService.update(u, id, body);
    }

    @PostMapping("/{id}/offline")
    public Result<String> offline(@PathVariable Long id) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (supplyBlocked(u)) return Result.error(403, MSG_SUPPLY_OFF);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id FROM demand_post WHERE id = ?", id);
        if (rows.isEmpty()) return Result.error("记录不存在");
        boolean owner = Objects.equals(u.getId(), toLong(rows.get(0).get("user_id")));
        if (!owner && !"ADMIN".equals(u.getRole())) return Result.error(403, "只能下架自己发布的信息");
        jdbcTemplate.update("UPDATE demand_post SET status = 'offline' WHERE id = ?", id);
        if (!owner) {
            try {
                auditService.record("ADMIN_OFFLINE_DEMAND", "BUSINESS", "DEMAND", String.valueOf(id),
                        null, null, 1, "WARN", "管理员下架他人发布的供需信息");
            } catch (Exception ignore) { }
        }
        return Result.ok("已下架");
    }

    /** 删除供需信息：本人可删自己的，管理员可删任意；删除不可恢复（下架才是可逆的） */
    @DeleteMapping("/{id}")
    public Result<String> deleteDemand(@PathVariable Long id) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, title FROM demand_post WHERE id = ?", id);
        if (rows.isEmpty()) return Result.error("记录不存在");
        boolean owner = Objects.equals(u.getId(), toLong(rows.get(0).get("user_id")));
        boolean admin = "ADMIN".equals(u.getRole());
        if (!owner && !admin) return Result.error(403, "只能删除自己发布的信息");
        String title = str(rows.get(0).get("title"));
        jdbcTemplate.update("DELETE FROM demand_post WHERE id = ?", id);
        try {
            auditService.record(admin && !owner ? "ADMIN_DELETE_DEMAND" : "DELETE_DEMAND",
                    "BUSINESS", "DEMAND", String.valueOf(id), null, null, 1, "WARN",
                    (admin && !owner ? "管理员删除他人供需信息：" : "删除供需信息：") + title);
        } catch (Exception ignore) { }
        return Result.ok("已删除");
    }
}
