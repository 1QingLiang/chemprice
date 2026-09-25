package com.datamarket.service;

import com.datamarket.common.Result;
import com.datamarket.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 供需发布/查询的统一业务入口：页面表单（DemandController）与 AI 智能问价都走这里，
 * 保证「认证校验 / 每日限频 / 有效期 / 打码」等规则只有一份实现。
 */
@Service
@RequiredArgsConstructor
public class DemandService {

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    /** 每人每日发布上限 */
    public static final int DAILY_POST_LIMIT = 5;
    /** 供需信息有效期（天） */
    public static final int EXPIRE_DAYS = 30;

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    /** 供应商认证状态：0未认证 1审核中 2已认证 3已驳回 */
    /**
     * 供应商认证状态（含有效期兜底）。
     * status: 0 未认证 / 1 审核中 / 2 已认证 / 3 需重新提交。
     * 若 status=2 但企业认证已过期（supplier_verify.valid_until < 今天），
     * 一律按 3（需重新提交认证）返回——过期即失去发布与查看完整联系方式的资格。
     */
    public Integer supplierStatusOf(Long userId) {
        if (userId == null) return 0;
        List<Integer> r = jdbcTemplate.queryForList(
                "SELECT supplier_status FROM sys_user WHERE id = ?", Integer.class, userId);
        if (r.isEmpty() || r.get(0) == null) return 0;
        int st = r.get(0);
        if (st == 2 && isSupplierExpired(userId)) return 3;
        return st;
    }

    /** 企业认证是否已过期：取最新一条已通过记录的 valid_until，早于今天即过期（无日期视为不过期） */
    public boolean isSupplierExpired(Long userId) {
        try {
            List<Object> r = jdbcTemplate.queryForList(
                    "SELECT valid_until FROM supplier_verify WHERE user_id = ? AND status = 'approved' " +
                    "ORDER BY id DESC LIMIT 1", Object.class, userId);
            if (r.isEmpty() || r.get(0) == null) return false;
            java.time.LocalDate until = java.time.LocalDate.parse(String.valueOf(r.get(0)).substring(0, 10));
            return until.isBefore(java.time.LocalDate.now());
        } catch (Exception e) {
            return false;
        }
    }

    /** 账号绑定的手机号（AI 一句话发布用） */
    public String phoneOf(Long userId) {
        List<String> r = jdbcTemplate.queryForList(
                "SELECT IFNULL(phone,'') FROM sys_user WHERE id = ?", String.class, userId);
        return r.isEmpty() ? "" : r.get(0);
    }

    /**
     * 记住用户本次发布实际填写的联系方式（下次发布 / AI 代发优先使用）。
     * 只更新非空字段，避免把已有的值清掉。
     */
    public void rememberContact(Long userId, String name, String phone, String email) {
        try {
            jdbcTemplate.update(
                    "UPDATE sys_user SET default_contact_name = IFNULL(?, default_contact_name), "
                  + "default_contact_phone = IFNULL(?, default_contact_phone), "
                  + "default_contact_email = IFNULL(?, default_contact_email) WHERE id = ?",
                    (name == null || name.isBlank()) ? null : name.trim(),
                    (phone == null || phone.isBlank()) ? null : phone.trim(),
                    (email == null || email.isBlank()) ? null : email.trim(),
                    userId);
        } catch (Exception ignore) { }
    }

    /**
     * 取用户已保存的默认联系方式，供 AI 代发优先使用。
     * 返回 [name, phone, email]，未设置的位置为空串。
     */
    public String[] defaultContactOf(Long userId) {
        try {
            java.util.List<Map<String, Object>> r = jdbcTemplate.queryForList(
                    "SELECT IFNULL(default_contact_name,'') n, IFNULL(default_contact_phone,'') p, "
                  + "IFNULL(default_contact_email,'') e FROM sys_user WHERE id = ?", userId);
            if (r.isEmpty()) return new String[]{"", "", ""};
            Map<String, Object> m = r.get(0);
            return new String[]{str(m.get("n")), str(m.get("p")), str(m.get("e"))};
        } catch (Exception e) {
            return new String[]{"", "", ""};
        }
    }

    public static String maskPhone(String p) {
        if (p == null || p.isBlank()) return null;
        String s = p.trim();
        if (s.length() < 7) return "***";
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }

    public static String maskEmail(String e) {
        if (e == null || e.isBlank()) return null;
        String s = e.trim();
        int at = s.indexOf('@');
        if (at <= 0) return "***";
        String local = s.substring(0, at);
        String domain = s.substring(at);
        String head = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return head + "***" + domain;
    }

    /** 发布一条供需信息（supply 需已认证供应商；每人每日限 DAILY_POST_LIMIT 条） */
    /** 供需模块总开关 */
    private boolean supplyOff() {
        List<String> r = jdbcTemplate.queryForList(
                "SELECT setting_value FROM site_setting WHERE setting_key = 'supply_enabled'", String.class);
        return r.isEmpty() || !"1".equals(r.get(0));
    }

    /** 危险化学品/易制毒易制爆名单（site_setting: dangerous_chem_keywords，逗号/顿号分隔，管理员可在供应商审核页维护） */
    public static final String DEFAULT_DANGEROUS = "液氯,氯气,液氨,氨气,硫化氢,光气,氢氟酸,氟化氢,溴素,氰化钾,氰化钠,氰化氢,三氧化二砷,砒霜,高锰酸钾,高氯酸,氯酸钾,硝酸铵,硝化甘油,硝化棉,TNT,炸药,雷管,导火索,黑火药,烟花爆竹,液化石油气,汽油,煤油,石脑油,石油醚,乙醚,丙酮,丁酮,三氯甲烷,氯仿,二氯甲烷,四氯化碳,双氧水,过氧化氢,过氧乙酸,过氧化苯甲酰,电石,黄磷,白磷,金属钠,金属钾,金属锂,镁粉,铝粉,锌粉,硫磺,赤磷,盐酸,硫酸,硝酸,醋酸酐,乙酸酐,甲苯,二甲苯,甲醇,乙醇,吡啶,哌啶,麻黄素,麻黄碱,苯丙胺,重铬酸钾,四氢呋喃,甲基叔丁基醚,LPG,Methanol,Ethanol,Toluene,Xylene,Acetone,MEK,THF,MTBE,Ether,Chloroform,Hydrogen Peroxide,Peracetic Acid,Hydrofluoric,Phosgene,Hydrogen Sulfide,Liquid Ammonia,Chlorine,Sodium Cyanide,Potassium Cyanide,Ammonium Nitrate,Calcium Carbide,Kerosene,Gasoline,Petrol,Sulfuric Acid,Hydrochloric Acid,Nitric Acid,Acetic Anhydride,Carbon Tetrachloride,White Phosphorus,Yellow Phosphorus,Red Phosphorus,Petroleum Ether,Diethyl Ether,CTC,DCM,H2O2,KMnO4,甲醛,福尔马林,Formaldehyde,Formalin,苯,Benzene,苯酚,石炭酸,Phenol,苯乙烯,Styrene,环氧乙烷,Ethylene Oxide,环氧丙烷,Propylene Oxide,丙烯腈,Acrylonitrile,氯乙烯,Vinyl Chloride,苯胺,Aniline,乙炔,Acetylene,氢气,Hydrogen,一氧化碳,Carbon Monoxide,天然气,Natural Gas,氯甲烷,Methyl Chloride,二氯乙烷,Dichloroethane,三氯乙烯,Trichloroethylene,四氯乙烯,Perchloroethylene,氯苯,Chlorobenzene,硝酸钾,Potassium Nitrate,硫化钠,Sodium Sulfide,纯苯,加氢苯,邻二甲苯,间二甲苯,对二甲苯,一氯甲烷,氯化苯,亚硝酸钠,硝酸钠,硝酸铵钙,硫磺粉,燃料乙醇,丙酮氰醇";

    /** 危化品发布限制<b>总开关</b>（site_setting: dangerous_chem_check；'0' = 关闭限制，缺省为开启） */
    public boolean dangerousCheckEnabled() {
        try {
            java.util.List<String> r = jdbcTemplate.queryForList(
                    "SELECT setting_value FROM site_setting WHERE setting_key = 'dangerous_chem_check'", String.class);
            return r.isEmpty() || r.get(0) == null || !"0".equals(r.get(0).trim());
        } catch (Exception e) {
            return true;
        }
    }

    /** 当前生效的危化品关键词串（名单为空 = 不拦截） */
    public String dangerousKeywords() {
        List<String> r = jdbcTemplate.queryForList(
                "SELECT setting_value FROM site_setting WHERE setting_key = 'dangerous_chem_keywords'", String.class);
        return r.isEmpty() || r.get(0) == null || r.get(0).trim().isEmpty()
                ? DEFAULT_DANGEROUS : r.get(0);
    }

    /**
     * 对外暴露的单品名危化品判定：命中返回命中的关键词，否则返回 null。
     * 供 AiChatService 在发布前逐行预过滤（把安全品种与违禁品种拆开发布）。
     */
    public String dangerousHitName(String productName) {
        return dangerousHit(productName);
    }

    /**
     * 归一化：用于关键词匹配前的「抗混淆」处理。
     * 去空白与常见分隔符、全角转半角、英文转小写 —— 拦住「甲 醇」「ＭＥＴＨＡＮＯＬ」这类低级绕过。
     */
    private static String normForMatch(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c) || c == '\u3000') continue;   // 半角+全角空格
            if (c >= 0xFF01 && c <= 0xFF5E) {                            // 全角 ASCII -> 半角
                c = (char) (c - 0xFEE0);
            }
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** 匹配前要忽略的「修饰词」：等级、纯度、包装形态等（去掉后仍应是同一物质） */
    private static final String[] MATCH_NOISE = {
            "工业级", "分析纯", "化学纯", "优级纯", "工业", "试剂", "精制", "进口", "国产",
            "散装", "桶装", "槽车", "合格品", "含量", "纯度", "液体", "固体", "粉末", "颗粒", "级"
    };

    /**
     * 宽松归一化：再去掉分隔符、含量数字/百分号与修饰词，用于「是不是同一物质」的比对。
     * 例：工业甲醇 → 甲醇；甲醇99.9% → 甲醇；31%盐酸 → 盐酸。
     */
    private static String normLoose(String s) {
        String t = normForMatch(s).replaceAll("[\\s\\-_/()（）·,，:：;；、%％\\[\\]【】。]+", "");
        t = t.replaceAll("[0-9.]+", "");
        for (String q : MATCH_NOISE) t = t.replace(q, "");
        return t;
    }

    /**
     * 产品名命中危化品名单则返回命中的关键词，否则返回 null。
     *
     * ⚠️ 2026-09-17 由「子串包含」改为「归一化后整词相等」（用户反馈 PVC 发不出去暴露的）：
     *   旧实现用 contains —— "聚氯乙烯".contains("氯乙烯") 为真 → PVC 被当成危化品；
     *   而且单字词条「苯」会把 苯乙烯/苯酐/间苯二甲酸 等一大片全连带拦住。
     *   实测：平台 341 个品种里 **64 个被拦，其中 43 个是误伤**。
     *   现在只有「产品名本身就是该物质」才拦；同系物/衍生物（邻二甲苯、一氯甲烷、纯苯…）
     *   作为独立词条写进名单，不再依赖长名字的连带命中。
     *   带修饰的写法仍拦得住：先整词比，再去掉含量数字/等级/包装等修饰词比。
     */
    private String dangerousHit(String productName) {
        if (!dangerousCheckEnabled()) return null;   // 总开关关闭时不做任何拦截
        if (productName == null || productName.isEmpty()) return null;
        String norm = normForMatch(productName);
        String loose = normLoose(productName);
        for (String k : dangerousKeywords().split("[,，、;；\r\n]+")) {
            String t = k.trim();
            if (t.isEmpty()) continue;
            String nt = normForMatch(t);
            if (nt.isEmpty()) continue;
            if (norm.equals(nt)) return t;                                  // ① 整词相等
            String lt = normLoose(t);
            if (!loose.isEmpty() && !lt.isEmpty() && loose.equals(lt)) return t;   // ② 去修饰后相等
        }
        return null;
    }

    /**
     * 逐行校验供货明细是否命中危化品名单。
     * 必须逐行查，不能只查拼接后的 varieties_name：
     * 拼接串超过 100 字会被 namesOf 截断，被截掉的行就漏检了（2026-09-16 实测复现）。
     */
    private String dangerousHitItems(java.util.List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return null;
        for (Map<String, Object> it : items) {
            if (it == null) continue;
            String hit = dangerousHit(str(it.get("name")));
            if (hit != null) return hit;
        }
        return null;
    }

    // ---------------- 多行供货明细（items） ----------------

    /** JSON 转义 */
    private static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * 规范化前端传来的供货明细数组。
     * 每行：{name, quantity, unit, spec, expectPrice, remark}；name 为空的行丢弃。
     * 返回 null 表示没有有效明细。
     */
    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> normItems(Object raw) {
        if (!(raw instanceof java.util.List<?> list)) return null;
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> src = (Map<String, Object>) m;
            String name = str(src.get("name"));
            if (name.isEmpty()) continue;
            Map<String, Object> it = new java.util.LinkedHashMap<>();
            it.put("name", name.length() > 100 ? name.substring(0, 100) : name);
            Object qty = numOrNull(src.get("quantity"));
            if (qty != null) it.put("quantity", qty);
            String unit = str(src.get("unit"));
            if (!unit.isEmpty()) it.put("unit", unit.length() > 16 ? unit.substring(0, 16) : unit);
            String spec = str(src.get("spec"));
            if (!spec.isEmpty()) it.put("spec", spec.length() > 128 ? spec.substring(0, 128) : spec);
            Object price = numOrNull(src.get("expectPrice"));
            if (price != null) it.put("expectPrice", price);
            String remark = str(src.get("remark"));
            if (!remark.isEmpty()) it.put("remark", remark.length() > 200 ? remark.substring(0, 200) : remark);
            out.add(it);
            if (out.size() >= 50) break;   // 单帖上限 50 行
        }
        return out.isEmpty() ? null : out;
    }

    /** 明细数组 -> JSON 字符串 */
    private static String itemsJson(java.util.List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return null;
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> it = items.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":\"").append(jsonEsc(String.valueOf(it.get("name")))).append('"');
            if (it.get("quantity") != null) b.append(",\"quantity\":").append(it.get("quantity"));
            if (it.get("unit") != null) b.append(",\"unit\":\"").append(jsonEsc(String.valueOf(it.get("unit")))).append('"');
            if (it.get("spec") != null) b.append(",\"spec\":\"").append(jsonEsc(String.valueOf(it.get("spec")))).append('"');
            if (it.get("expectPrice") != null) b.append(",\"expectPrice\":").append(it.get("expectPrice"));
            if (it.get("remark") != null) b.append(",\"remark\":\"").append(jsonEsc(String.valueOf(it.get("remark")))).append('"');
            b.append('}');
        }
        return b.append(']').toString();
    }

    /** 全部明细的品种名拼接（兼容旧的 varieties_name 单字段消费方） */
    private static String namesOf(java.util.List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> it : items) {
            if (b.length() > 0) b.append('、');
            b.append(String.valueOf(it.get("name")));
        }
        String s = b.toString();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private Integer realnameStatusOf(Long userId) {
        List<Integer> r = jdbcTemplate.queryForList(
                "SELECT realname_status FROM sys_user WHERE id = ?", Integer.class, userId);
        return r.isEmpty() ? null : r.get(0);
    }

    public Result<Map<String, Object>> publish(SysUser u, Map<String, Object> body) {
        if (supplyOff() && !"ADMIN".equals(u.getRole())) {
            return Result.error(403, "供需对接功能暂未开放，请稍后再来");
        }
        String type = str(body.get("type"));
        if (!"demand".equals(type) && !"supply".equals(type)) {
            return Result.error("发布类型不正确");
        }
        if ("supply".equals(type) && supplierStatusOf(u.getId()) != 2) {
            return Result.error("发布供应信息需要先通过供应商认证");
        }
        // 实名认证：发布求购/供应都需要先实名（免费，公安库二要素核验；管理员豁免）
        if (!"ADMIN".equals(u.getRole()) && realnameStatusOf(u.getId()) != 1) {
            return Result.error(403, "发布供需需要先完成实名认证（免费），请到「供应商认证」页办理");
        }

        String title = str(body.get("title"));
        if (title.isEmpty()) return Result.error("请填写标题");
        if (title.length() > 120) return Result.error("标题过长（最多 120 字）");

        // 多行供货明细（方案 A：items 存 JSON，第一行同时写回旧字段）
        java.util.List<Map<String, Object>> items = normItems(body.get("items"));
        String vname;
        Map<String, Object> first = null;
        if (items != null) {
            first = items.get(0);
            vname = namesOf(items);
        } else {
            // 兼容旧调用方（AI 代发等仍传 varietiesName + quantity/spec/expectPrice）
            vname = str(body.get("varietiesName"));
            if (!vname.isEmpty()) {
                Map<String, Object> one = new java.util.LinkedHashMap<>();
                one.put("name", vname);
                Object q0 = numOrNull(body.get("quantity"));
                if (q0 != null) one.put("quantity", q0);
                String u0 = str(body.get("unit"));
                if (!u0.isEmpty()) one.put("unit", u0);
                String s0 = str(body.get("spec"));
                if (!s0.isEmpty()) one.put("spec", s0);
                Object p0 = numOrNull(body.get("expectPrice"));
                if (p0 != null) one.put("expectPrice", p0);
                items = new java.util.ArrayList<>();
                items.add(one);
                first = one;
            }
        }
        if (vname.isEmpty()) return Result.error("请至少填写一行产品名称");

        String contactName = str(body.get("contactName"));
        String phone = str(body.get("contactPhone")).replaceAll("[^0-9]", "");
        String email = str(body.get("contactEmail"));
        String err = basicCheck(title, vname, contactName, phone, email);
        if (err != null) return Result.error(err);
        // 求购必须逐行填写数量（供应选填）。放在基础校验之后：手机号未绑定等账号级阻断无法靠改内容解决，优先提示
        String qtyErr = demandQtyCheck(type, items, body.get("quantity"));
        if (qtyErr != null) return Result.error(qtyErr);
        // 逐行校验每一行明细（不能只查拼接串，见 dangerousHitItems 注释）；拼接串兜底
        String dg = dangerousHitItems(items);
        if (dg == null) dg = dangerousHit(vname);
        if (dg != null) return Result.error("「" + dg + "」属于危险化学品/易制毒易制爆品类，平台禁止发布");

        Long today = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demand_post WHERE user_id = ? AND created_at >= CURDATE()",
                Long.class, u.getId());
        if (today != null && today >= DAILY_POST_LIMIT) {
            return Result.error("今日发布已达上限（" + DAILY_POST_LIMIT + " 条），请明天再发");
        }

        Integer varietiesId = null;
        Object vid = body.get("varietiesId");
        if (vid instanceof Number n) varietiesId = n.intValue();
        else if (vid != null && !str(vid).isEmpty()) {
            try { varietiesId = Integer.parseInt(str(vid)); } catch (Exception ignore) { }
        }

        String company = str(body.get("companyName"));
        if ("supply".equals(type) && company.isEmpty()) {
            List<String> cs = jdbcTemplate.queryForList(
                    "SELECT IFNULL(supplier_company,'') FROM sys_user WHERE id = ?",
                    String.class, u.getId());
            company = cs.isEmpty() ? "" : cs.get(0);
        }

        String remark = str(body.get("remark"));
        if (remark.length() > 1000) remark = remark.substring(0, 1000);

        // 第一行写回旧字段，保证列表页/AI/公众号等既有消费方不受影响
        Object qty1 = first == null ? numOrNull(body.get("quantity")) : first.get("quantity");
        String unit1 = first == null ? str(body.get("unit")) : str(first.get("unit"));
        if (unit1.isEmpty()) unit1 = "吨";
        Object spec1 = first == null ? emptyToNull(body.get("spec")) : first.get("spec");
        Object price1 = first == null ? numOrNull(body.get("expectPrice")) : first.get("expectPrice");

        jdbcTemplate.update(
                "INSERT INTO demand_post (type, user_id, title, varieties_id, varieties_name, items, quantity, " +
                "unit, spec, expect_price, delivery_date, region, remark, contact_name, contact_phone, " +
                "contact_email, company_name, status, expire_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'online', DATE_ADD(NOW(), INTERVAL ? DAY))",
                type, u.getId(), title, varietiesId, vname, itemsJson(items),
                qty1, unit1, spec1, price1,
                dateOrNull(body.get("deliveryDate")), emptyToNull(body.get("region")),
                remark.isEmpty() ? null : remark, contactName,
                phone.isEmpty() ? null : phone, email.isEmpty() ? null : email,
                company.isEmpty() ? null : company, EXPIRE_DAYS);

        // 首次发布时把手机号与账号绑定（之后 AI 一句话发布可直接使用）
        try {
            List<String> have = jdbcTemplate.queryForList(
                    "SELECT IFNULL(phone,'') FROM sys_user WHERE id = ?", String.class, u.getId());
            if (have.isEmpty() || have.get(0).isBlank()) {
                jdbcTemplate.update("UPDATE sys_user SET phone = ? WHERE id = ?", phone, u.getId());
            }
        } catch (Exception ignore) { }

        // 记住本次实际填写的联系方式 —— 下次发布（含 AI 代发）自动带出，避免用错联系人
        rememberContact(u.getId(), contactName, phone, email);

        try {
            auditService.record("PUBLISH_DEMAND", "BUSINESS", "DEMAND", String.valueOf(u.getId()),
                    null, null, 1, "INFO",
                    ("supply".equals(type) ? "发布供应：" : "发布需求：") + title);
        } catch (Exception ignore) { }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return Result.ok(out);
    }

    /** 标题/产品/联系人/手机号/邮箱 的公共校验；返回 null 表示通过 */
    private String basicCheck(String title, String vname, String contactName,
                              String phone, String email) {
        if (title.isEmpty()) return "请填写标题";
        if (title.length() > 120) return "标题过长（最多 120 字）";
        if (vname.isEmpty()) return "请填写产品名称";
        if (contactName.isEmpty()) return "请填写联系人";
        // 手机号必填：买家要能直接电话联系；邮箱选填
        if (phone.isEmpty()) return "请填写手机号（便于对方直接电话联系你）";
        if (!PHONE.matcher(phone).matches()) return "请填写正确的 11 位手机号";
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) return "邮箱格式不正确";
        return null;
    }

    /**
     * 求购（demand）必须逐行填写数量；供应（supply）选填。
     * <p>数量是求购的核心要素——供应商需要据此判断能否接单，缺失会让报价无从谈起。
     * items 为空时回退到 fallbackQty（兼容 AI 代发等只传单值的旧调用方）。
     * 返回 null 表示通过。
     */
    private static String demandQtyCheck(String type, java.util.List<Map<String, Object>> items,
                                         Object fallbackQty) {
        if (!"demand".equals(type)) return null;
        if (items != null && !items.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                Object q = items.get(i).get("quantity");
                if (q == null || str(q).isEmpty()) {
                    return items.size() > 1
                            ? "求购需要填写数量：第 " + (i + 1) + " 行「" + str(items.get(i).get("name")) + "」缺少数量"
                            : "求购需要填写数量，请补充数量后再提交";
                }
            }
            return null;
        }
        if (fallbackQty == null || str(fallbackQty).isEmpty()) {
            return "求购需要填写数量，请补充数量后再提交";
        }
        return null;
    }

    /** 修改自己发布的供需信息（仅本人；仅「展示中」可改；type 不可变） */
    public Result<Map<String, Object>> update(SysUser u, Long id, Map<String, Object> body) {
        if (supplyOff() && !"ADMIN".equals(u.getRole())) {
            return Result.error(403, "供需对接功能暂未开放，请稍后再来");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, type, status, title, varieties_name, items, quantity, unit, spec, " +
                "expect_price, delivery_date, region, remark, contact_name, contact_phone, " +
                "contact_email, company_name FROM demand_post WHERE id = ?", id);
        if (rows.isEmpty()) return Result.error("该供需信息不存在");
        Map<String, Object> old = rows.get(0);
        if (!Objects.equals(u.getId(),
                old.get("user_id") instanceof Number n ? n.longValue() : null)) {
            return Result.error(403, "只能修改自己发布的信息");
        }
        String oldStatus = str(old.get("status"));
        if ("rejected".equals(oldStatus)) {
            return Result.error("该信息已被驳回，无法修改");
        }
        // 已下架 / 已过期也允许编辑：保存后自动重新上架，有效期顺延 30 天
        boolean republish = !"online".equals(oldStatus);

        String type = str(old.get("type"));
        String title = str(body.get("title")).isEmpty() ? str(old.get("title")) : str(body.get("title"));

        // 多行供货明细：传了 items 就用新值，否则沿用旧值
        java.util.List<Map<String, Object>> items = normItems(body.get("items"));
        boolean useItems = items != null;
        Object qty1 = null, spec1 = null, price1 = null;
        String unit1 = null;
        String vname;
        if (useItems) {
            Map<String, Object> first = items.get(0);
            vname = namesOf(items);
            qty1 = first.get("quantity");
            unit1 = str(first.get("unit"));
            spec1 = first.get("spec");
            price1 = first.get("expectPrice");
        } else {
            vname = str(body.get("varietiesName")).isEmpty()
                    ? str(old.get("varieties_name")) : str(body.get("varietiesName"));
            items = normItems(body.get("itemsReuse"));
        }
        String contactName = str(body.get("contactName")).isEmpty()
                ? str(old.get("contact_name")) : str(body.get("contactName"));
        String phone = str(body.get("contactPhone")).replaceAll("[^0-9]", "");
        if (phone.isEmpty()) phone = str(old.get("contact_phone")).replaceAll("[^0-9]", "");
        String email = str(body.get("contactEmail")).isEmpty()
                ? str(old.get("contact_email")) : str(body.get("contactEmail"));

        String err = basicCheck(title, vname, contactName, phone, email);
        if (err != null) return Result.error(err);
        // 逐行校验每一行明细（不能只查拼接串，见 dangerousHitItems 注释）；拼接串兜底
        String dg = dangerousHitItems(items);
        if (dg == null) dg = dangerousHit(vname);
        if (dg != null) return Result.error("「" + dg + "」属于危险化学品/易制毒易制爆品类，平台禁止发布");

        Integer varietiesId = null;
        Object vid = body.get("varietiesId");
        if (vid instanceof Number n) varietiesId = n.intValue();
        else if (vid != null && !str(vid).isEmpty()) {
            try { varietiesId = Integer.parseInt(str(vid)); } catch (Exception ignore) { }
        }
        String unit = useItems
                ? (unit1 == null || unit1.isEmpty() ? "吨" : unit1)
                : (str(body.get("unit")).isEmpty() ? str(old.get("unit")) : str(body.get("unit")));
        if (unit.isEmpty()) unit = "吨";
        String company = str(body.get("companyName")).isEmpty()
                ? str(old.get("company_name")) : str(body.get("companyName"));
        String remark = str(body.get("remark")).isEmpty()
                ? str(old.get("remark")) : str(body.get("remark"));
        if (remark.length() > 1000) remark = remark.substring(0, 1000);
        String spec = useItems
                ? (spec1 == null ? null : str(spec1))
                : (str(body.get("spec")).isEmpty() ? str(old.get("spec")) : str(body.get("spec")));
        String region = str(body.get("region")).isEmpty() ? str(old.get("region")) : str(body.get("region"));
        Object quantity = useItems
                ? qty1
                : (body.containsKey("quantity") ? numOrNull(body.get("quantity")) : old.get("quantity"));
        Object expectPrice = useItems
                ? price1
                : (body.containsKey("expectPrice") ? numOrNull(body.get("expectPrice")) : old.get("expect_price"));
        // 求购必须逐行填写数量（供应选填）；type 不可变，沿用原帖类型
        String qtyErr = demandQtyCheck(type, items, quantity);
        if (qtyErr != null) return Result.error(qtyErr);
        String itemsCol = itemsJson(items);
        Object deliveryDate = str(body.get("deliveryDate")).isEmpty()
                ? old.get("delivery_date") : dateOrNull(body.get("deliveryDate"));

        // 注意 SET 顺序：expire_at 的 CASE 必须在 status 赋值之前求值（MySQL 按顺序求值）
        jdbcTemplate.update(
                "UPDATE demand_post SET title=?, varieties_id=?, varieties_name=?, items=?, quantity=?, unit=?, " +
                "spec=?, expect_price=?, delivery_date=?, region=?, remark=?, contact_name=?, " +
                "contact_phone=?, contact_email=?, company_name=?, " +
                "expire_at=CASE WHEN status='online' THEN expire_at ELSE DATE_ADD(NOW(), INTERVAL 30 DAY) END, " +
                "status=CASE WHEN status='online' THEN status ELSE 'online' END " +
                "WHERE id=? AND user_id=?",
                title, varietiesId, vname, itemsCol, quantity, unit, spec, expectPrice, deliveryDate,
                region, remark.isEmpty() ? null : remark, contactName,
                phone.isEmpty() ? null : phone, email.isEmpty() ? null : email,
                company.isEmpty() ? null : company, id, u.getId());

        try {
            auditService.record(republish ? "REPUBLISH_DEMAND" : "UPDATE_DEMAND", "BUSINESS", "DEMAND",
                    String.valueOf(id), null, null, 1, "INFO",
                    (republish ? "编辑并重新上架供需信息：" : "修改供需信息：") + title);
        } catch (Exception ignore) { }

        // 改帖也记住联系方式（下次发布 / AI 代发优先使用）
        rememberContact(u.getId(), contactName, phone, email);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("republished", republish);   // 已下架/已过期帖保存后会自动重新上架
        return Result.ok(out);
    }

    private static Object numOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try { return new java.math.BigDecimal(s); } catch (Exception e) { return null; }
    }

    private static String emptyToNull(Object o) {
        String s = str(o);
        return s.isEmpty() ? null : s;
    }

    private static Object dateOrNull(Object o) {
        String s = str(o);
        if (s.isEmpty()) return null;
        try { return java.sql.Date.valueOf(s.substring(0, 10)); } catch (Exception e) { return null; }
    }

    /** 供给列表的一个轻量装配（AI 用）：把「供/求 + 产品 + 数量」拼成标题 */
    public static String buildTitle(String type, String product, String quantity, String unit) {
        StringBuilder t = new StringBuilder();
        t.append("supply".equals(type) ? "供应" : "求购").append(product);
        if (quantity != null && !quantity.isBlank()) {
            t.append(' ').append(quantity.trim()).append(unit == null || unit.isBlank() ? "吨" : unit.trim());
        }
        return t.toString();
    }
}
