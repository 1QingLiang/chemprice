package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.entity.SysUser;
import com.datamarket.security.PermissionHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 企业分布图（/api/enterprise-map/**）。
 *
 * <p>数据源：enterprise_province 表（440 家企业 → 30 个省份的映射，已前置核实）。
 * 本接口只做聚合与下钻，不重算省份归属。
 *
 * <p>访问控制：<b>用户级开关</b> sys_user.enterprise_map_enabled。
 * ADMIN 始终可见；普通用户需管理员单独开启。
 * 未开启时数据接口返回 403，前端据此展示引导态而非报错。
 */
@RestController
@RequestMapping("/api/enterprise-map")
public class EnterpriseMapController {

    private final JdbcTemplate jdbcTemplate;
    private final PermissionHelper permissionHelper;

    public EnterpriseMapController(JdbcTemplate jdbcTemplate, PermissionHelper permissionHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionHelper = permissionHelper;
    }

    /** 省份中心坐标（用于地图光晕与标签定位），键为省份简称。 */
    private static final Map<String, double[]> PROV_LL = new LinkedHashMap<>();
    static {
        PROV_LL.put("北京", new double[]{116.405, 39.905});
        PROV_LL.put("天津", new double[]{117.190, 39.125});
        PROV_LL.put("河北", new double[]{114.502, 38.045});
        PROV_LL.put("山西", new double[]{112.549, 37.857});
        PROV_LL.put("内蒙古", new double[]{111.670, 40.818});
        PROV_LL.put("辽宁", new double[]{123.429, 41.796});
        PROV_LL.put("吉林", new double[]{125.324, 43.887});
        PROV_LL.put("黑龙江", new double[]{126.642, 45.757});
        PROV_LL.put("上海", new double[]{121.473, 31.230});
        PROV_LL.put("江苏", new double[]{118.767, 32.041});
        PROV_LL.put("浙江", new double[]{120.153, 30.287});
        PROV_LL.put("安徽", new double[]{117.283, 31.861});
        PROV_LL.put("福建", new double[]{119.306, 26.075});
        PROV_LL.put("江西", new double[]{115.858, 28.683});
        PROV_LL.put("山东", new double[]{117.000, 36.651});
        PROV_LL.put("河南", new double[]{113.665, 34.758});
        PROV_LL.put("湖北", new double[]{114.298, 30.584});
        PROV_LL.put("湖南", new double[]{112.982, 28.194});
        PROV_LL.put("广东", new double[]{113.280, 23.125});
        PROV_LL.put("广西", new double[]{108.320, 22.824});
        PROV_LL.put("海南", new double[]{110.331, 20.032});
        PROV_LL.put("重庆", new double[]{106.505, 29.533});
        PROV_LL.put("四川", new double[]{104.066, 30.659});
        PROV_LL.put("贵州", new double[]{106.713, 26.578});
        PROV_LL.put("云南", new double[]{102.712, 25.040});
        PROV_LL.put("西藏", new double[]{91.132, 29.660});
        PROV_LL.put("陕西", new double[]{108.948, 34.263});
        PROV_LL.put("甘肃", new double[]{103.823, 36.058});
        PROV_LL.put("青海", new double[]{101.778, 36.623});
        PROV_LL.put("宁夏", new double[]{106.278, 38.466});
        PROV_LL.put("新疆", new double[]{87.617, 43.792});
        PROV_LL.put("台湾", new double[]{121.509, 25.044});
        PROV_LL.put("中国香港", new double[]{114.173, 22.320});
        PROV_LL.put("中国澳门", new double[]{113.549, 22.199});
    }

    /**
     * 当前登录用户对该模块的可访问性（前端进页面先调这个）。
     * <p>返回 enabled=false 时前端展示引导态；管理者另有 admin=true 便于展示说明。
     */
    @GetMapping("/access")
    public Result<Map<String, Object>> access() {
        SysUser u = permissionHelper.getCurrentUser();
        Map<String, Object> out = new LinkedHashMap<>();
        if (u == null) {
            out.put("enabled", false);
            out.put("admin", false);
            out.put("reason", "未登录");
            return Result.ok(out);
        }
        boolean admin = "ADMIN".equals(u.getRole());
        // 有效期：开关打开 **且** 未过期才可用（到期日 NULL = 永久；到期日当天仍有效）
        Map<String, Object> auth = admin ? null : emapAuth(u.getId());
        boolean on = admin || Boolean.TRUE.equals(auth.get("enabled"));
        out.put("enabled", on);
        out.put("admin", admin);
        // 前端据此展示「有效期至 X（剩余 N 天）」/「已到期」
        out.put("switchOn", admin || Boolean.TRUE.equals(auth.get("switchOn")));
        out.put("expireDate", admin ? null : auth.get("expireDate"));
        out.put("daysLeft", admin ? null : auth.get("daysLeft"));
        out.put("expired", admin ? Boolean.FALSE : auth.get("expired"));
        if (on) {
            out.put("reason", "");
        } else if (Boolean.TRUE.equals(auth.get("expired"))) {
            out.put("reason", "权限已于 " + auth.get("expireDate") + " 到期，请联系管理员续期");
        } else {
            out.put("reason", "该功能需管理员开通");
        }
        return Result.ok(out);
    }

    /** 总览：KPI + 省份聚合（含坐标）。 */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");

        List<Map<String, Object>> provRows = jdbcTemplate.queryForList(
                "SELECT province, province_full, COUNT(*) AS entCount, " +
                "IFNULL(SUM(records),0) AS records, SUM(confidence='高') AS hiCount " +
                "FROM enterprise_province GROUP BY province, province_full " +
                "ORDER BY entCount DESC, province ASC");

        int totEnt = 0;
        long totRec = 0;
        long totHi = 0;
        List<Map<String, Object>> provinces = new ArrayList<>();
        for (Map<String, Object> r : provRows) {
            String p = str(r.get("province"));
            int ent = num(r.get("entCount"));
            long rec = num(r.get("records"));
            long hi = num(r.get("hiCount"));
            totEnt += ent;
            totRec += rec;
            totHi += hi;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("province", p);
            m.put("provinceFull", str(r.get("province_full")));
            m.put("entCount", ent);
            m.put("records", rec);
            m.put("hiCount", hi);
            m.put("midCount", ent - hi);
            double[] ll = PROV_LL.get(p);
            m.put("lng", ll != null ? ll[0] : null);
            m.put("lat", ll != null ? ll[1] : null);
            provinces.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalEnterprises", totEnt);
        out.put("totalProvinces", provinces.size());
        out.put("totalRecords", totRec);
        out.put("verifiedCount", totHi);
        out.put("pendingCount", totEnt - totHi);
        out.put("provinces", provinces);
        out.put("updatedAt", jdbcTemplate.queryForObject(
                "SELECT IFNULL(MAX(updated_at), NOW()) FROM enterprise_province", String.class));
        return Result.ok(out);
    }

    /**
     * 某省企业明细（下钻）。按报价记录数倒序。
     * <p>{@code province} 支持简称（山东）或全称（山东省）。
     */
    @GetMapping("/province/{province}")
    public Result<Map<String, Object>> province(@PathVariable String province) {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");

        String shortName = province;
        if (shortName.endsWith("省")) shortName = shortName.substring(0, shortName.length() - 1);
        else if (shortName.endsWith("市")) shortName = shortName.substring(0, shortName.length() - 1);
        else if (shortName.endsWith("自治区")) shortName = shortName.substring(0, shortName.length() - 3);
        else if (shortName.startsWith("内蒙古")) shortName = "内蒙古";
        else if (shortName.startsWith("广西")) shortName = "广西";
        else if (shortName.startsWith("宁夏")) shortName = "宁夏";
        else if (shortName.startsWith("新疆")) shortName = "新疆";
        else if (shortName.startsWith("西藏")) shortName = "西藏";
        final String key = shortName;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT brand_name, method, confidence, records, varieties_cnt, last_date " +
                "FROM enterprise_province WHERE province = ? ORDER BY records DESC, brand_name ASC", key);

        long recSum = 0;
        long hi = 0;
        List<Map<String, Object>> ents = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", str(r.get("brand_name")));
            m.put("records", num(r.get("records")));
            m.put("varieties", num(r.get("varieties_cnt")));
            m.put("lastDate", r.get("last_date") == null ? null : String.valueOf(r.get("last_date")));
            m.put("method", str(r.get("method")));
            m.put("confidence", str(r.get("confidence")));
            ents.add(m);
            recSum += num(r.get("records"));
            if ("高".equals(str(r.get("confidence")))) hi++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("province", key);
        out.put("entCount", ents.size());
        out.put("records", recSum);
        out.put("hiCount", hi);
        out.put("midCount", ents.size() - hi);
        out.put("enterprises", ents);
        return Result.ok(out);
    }


    // ==================== 产品维度（品种 → 省份分布） ====================
    // 口径：只统计有企业品牌（brand_name 非空）的报价 —— 市场价没有品牌，无法落到省份。
    // 省份归属复用 enterprise_province，与企业分布页签保持同一套口径。
    // ⭐ 两表 collation 已统一为 utf8mb4_general_ci，并建了 idx_vid_brand(varieties_id, brand_name)。
    // ⛔ 切勿再写 COLLATE 强制转换 —— 那会让索引失效，/varieties 从毫秒级退化到 10s+，
    //    直接撞穿前端 axios 的 10s timeout（表现为页面一直「正在加载品种…」且下拉为空）。

    private static final String BRANDED_JOIN =
            "FROM enterprise_price ep " +
            "JOIN enterprise_province p ON p.brand_name = ep.brand_name " +
            "WHERE ep.brand_name IS NOT NULL AND ep.brand_name <> '' ";

    /**
     * 可选品种清单（有企业报价的品种），按品牌数倒序。
     * <p>前端下拉用；同时给出品牌数/省份数/记录数，便于用户判断该品种数据是否够看。
     */
    @GetMapping("/varieties")
    public Result<Map<String, Object>> varieties() {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");

        // ⭐ 读预计算表（由 refresh_variety_stat.sh 维护）：
        // 直接 JOIN enterprise_price（7.46M 行）即使走覆盖索引也需 ~3.4s，
        // 会撞穿前端 axios 10s 超时。品种清单低频变化、高频读取 → 必须预计算。
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT varieties_id AS vid, varieties_name AS vname, " +
                "records, brands, provinces AS provs " +
                "FROM enterprise_variety_stat ORDER BY brands DESC, records DESC");

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("varietiesId", num(r.get("vid")));
            m.put("varietiesName", str(r.get("vname")));
            m.put("records", num(r.get("records")));
            m.put("brands", num(r.get("brands")));
            m.put("provinces", num(r.get("provs")));
            list.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", list.size());
        out.put("varieties", list);
        return Result.ok(out);
    }

    /**
     * 某品种的区域分布（下钻）。
     * <p>参数 {@code vid} 为 varieties_id；{@code name} 为品种名（二选一，vid 优先）。
     * <p>返回：该品种按省份聚合的 brands / records（地图着色用）+ 全国品牌明细（列表用）。
     */
    @GetMapping("/variety/{vid}")
    public Result<Map<String, Object>> variety(@PathVariable String vid,
                                               @RequestParam(required = false) String name) {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");

        List<Map<String, Object>> vrow;
        String vname;
        if (name != null && !name.isEmpty()) {
            vrow = jdbcTemplate.queryForList(
                    "SELECT DISTINCT ep.varieties_id AS vid, ep.varieties_name AS vname " +
                    "FROM enterprise_price ep WHERE ep.varieties_name = ? LIMIT 1", name);
        } else {
            int v = 0;
            try { v = Integer.parseInt(vid); } catch (Exception ignore) { }
            vrow = jdbcTemplate.queryForList(
                    "SELECT DISTINCT ep.varieties_id AS vid, ep.varieties_name AS vname " +
                    "FROM enterprise_price ep WHERE ep.varieties_id = ? LIMIT 1", v);
        }
        if (vrow.isEmpty()) return Result.error("品种不存在");
        int realVid = num(vrow.get(0).get("vid"));
        vname = str(vrow.get(0).get("vname"));

        // ① 省份聚合（地图着色）
        List<Map<String, Object>> provRows = jdbcTemplate.queryForList(
                "SELECT p.province AS province, p.province_full AS province_full, " +
                "COUNT(DISTINCT ep.brand_name) AS brands, COUNT(*) AS records " + BRANDED_JOIN +
                "AND ep.varieties_id = ? " +
                "GROUP BY p.province, p.province_full " +
                "ORDER BY brands DESC, records DESC", realVid);

        int totBrands = 0;
        long totRec = 0;
        List<Map<String, Object>> provinces = new ArrayList<>();
        for (Map<String, Object> r : provRows) {
            String pv = str(r.get("province"));
            int br = num(r.get("brands"));
            long rc = num(r.get("records"));
            totBrands += br;
            totRec += rc;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("province", pv);
            m.put("provinceFull", str(r.get("province_full")));
            m.put("brands", br);
            m.put("records", rc);
            double[] ll = PROV_LL.get(pv);
            m.put("lng", ll != null ? ll[0] : null);
            m.put("lat", ll != null ? ll[1] : null);
            provinces.add(m);
        }

        // ② 品牌明细（列表 + 下钻）
        List<Map<String, Object>> brandRows = jdbcTemplate.queryForList(
                "SELECT ep.brand_name AS brand, p.province AS province, p.confidence AS confidence, " +
                "COUNT(*) AS records, MAX(ep.data_date) AS last_date, " +
                "ROUND(AVG(NULLIF(ep.middle_price,0)),2) AS avg_price " + BRANDED_JOIN +
                "AND ep.varieties_id = ? " +
                "GROUP BY ep.brand_name, p.province, p.confidence " +
                "ORDER BY records DESC LIMIT 500", realVid);

        List<Map<String, Object>> brands = new ArrayList<>();
        for (Map<String, Object> r : brandRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", str(r.get("brand")));
            m.put("province", str(r.get("province")));
            m.put("confidence", str(r.get("confidence")));
            m.put("records", num(r.get("records")));
            m.put("avgPrice", r.get("avg_price") == null ? null : String.valueOf(r.get("avg_price")));
            m.put("lastDate", r.get("last_date") == null ? null : String.valueOf(r.get("last_date")));
            brands.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("varietiesId", realVid);
        out.put("varietiesName", vname);
        out.put("totalBrands", totBrands);
        out.put("totalProvinces", provinces.size());
        out.put("totalRecords", totRec);
        out.put("provinces", provinces);
        out.put("brands", brands);
        out.put("updatedAt", jdbcTemplate.queryForObject(
                "SELECT IFNULL(MAX(updated_at), NOW()) FROM enterprise_province", String.class));
        return Result.ok(out);
    }

    /** 管理员视角：全部用户的开关状态。 */
    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        SysUser me = permissionHelper.getCurrentUser();
        if (me == null || !"ADMIN".equals(me.getRole())) {
            return Result.error(403, "仅管理员可用");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, username, nickname, email, role, status, " +
                "IFNULL(enterprise_map_enabled,0) AS enabled, last_login_at " +
                "FROM sys_user ORDER BY enterprise_map_enabled DESC, id ASC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", num(r.get("id")));
            m.put("username", str(r.get("username")));
            m.put("nickname", str(r.get("nickname")));
            m.put("email", str(r.get("email")));
            m.put("role", str(r.get("role")));
            m.put("status", num(r.get("status")));
            m.put("enabled", num(r.get("enabled")));
            m.put("lastLoginAt", r.get("last_login_at") == null ? null : String.valueOf(r.get("last_login_at")));
            out.add(m);
        }
        return Result.ok(out);
    }

    /** 管理员：开关单个用户的分布图访问权限。 */
    @PutMapping("/users/{userId}")
    public Result<Object> toggleUser(@PathVariable long userId, @RequestBody Map<String, Object> body) {
        SysUser me = permissionHelper.getCurrentUser();
        if (me == null || !"ADMIN".equals(me.getRole())) {
            return Result.error(403, "仅管理员可用");
        }
        Object e = body.get("enabled");
        if (e == null) return Result.error("缺少 enabled 参数");

        String ev = String.valueOf(e);
        int on = (Boolean.TRUE.equals(e) || "1".equals(ev) || "true".equalsIgnoreCase(ev)) ? 1 : 0;

        List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                "SELECT id, username, role FROM sys_user WHERE id = ?", userId);
        if (hit.isEmpty()) return Result.error("用户不存在");

        // 管理员自身权限恒定，不允许把管理员置为关闭造成误解
        if ("ADMIN".equals(str(hit.get(0).get("role"))) && on == 0) {
            return Result.error("管理员始终具备该权限，无需关闭");
        }

        jdbcTemplate.update("UPDATE sys_user SET enterprise_map_enabled = ? WHERE id = ?", on, userId);
        return Result.ok(Map.of("id", userId, "enabled", on,
                "username", str(hit.get(0).get("username"))));
    }

    // ==================== 报价点维度：省市下钻（2026-09-25） ====================
    // 口径：以「报价点」ep.market_name 关联 enterprise_point（报价点→省市映射）。
    // 相比旧的「品牌」口径（仅 27 个品种），报价点口径覆盖 256 个品种 / 740 万行。
    // ⭐ 一律读预计算表 enterprise_point_stat（3,116 行，cron 06:40 刷新）→ 全部查询 < 4ms。
    // ⛔ 切勿直接 JOIN enterprise_price 做聚合：全量省级 19.2s、省+市级 26.9s，
    //    必撞穿 api/index.js 的 timeout:10000（客户端静默 abort → 页面卡 loading）。

    /** 省简称 → 全称（enterprise_point_stat 未存全称，这里补齐给前端展示）。 */
    private static final Map<String, String> PROV_FULL_CN = new LinkedHashMap<>();
    static {
        PROV_FULL_CN.put("北京", "北京市"); PROV_FULL_CN.put("天津", "天津市");
        PROV_FULL_CN.put("上海", "上海市"); PROV_FULL_CN.put("重庆", "重庆市");
        PROV_FULL_CN.put("河北", "河北省"); PROV_FULL_CN.put("山西", "山西省");
        PROV_FULL_CN.put("辽宁", "辽宁省"); PROV_FULL_CN.put("吉林", "吉林省");
        PROV_FULL_CN.put("黑龙江", "黑龙江省"); PROV_FULL_CN.put("江苏", "江苏省");
        PROV_FULL_CN.put("浙江", "浙江省"); PROV_FULL_CN.put("安徽", "安徽省");
        PROV_FULL_CN.put("福建", "福建省"); PROV_FULL_CN.put("江西", "江西省");
        PROV_FULL_CN.put("山东", "山东省"); PROV_FULL_CN.put("河南", "河南省");
        PROV_FULL_CN.put("湖北", "湖北省"); PROV_FULL_CN.put("湖南", "湖南省");
        PROV_FULL_CN.put("广东", "广东省"); PROV_FULL_CN.put("海南", "海南省");
        PROV_FULL_CN.put("四川", "四川省"); PROV_FULL_CN.put("贵州", "贵州省");
        PROV_FULL_CN.put("云南", "云南省"); PROV_FULL_CN.put("陕西", "陕西省");
        PROV_FULL_CN.put("甘肃", "甘肃省"); PROV_FULL_CN.put("青海", "青海省");
        PROV_FULL_CN.put("台湾", "台湾省"); PROV_FULL_CN.put("内蒙古", "内蒙古自治区");
        PROV_FULL_CN.put("广西", "广西壮族自治区"); PROV_FULL_CN.put("西藏", "西藏自治区");
        PROV_FULL_CN.put("宁夏", "宁夏回族自治区"); PROV_FULL_CN.put("新疆", "新疆维吾尔自治区");
        PROV_FULL_CN.put("中国香港", "中国香港"); PROV_FULL_CN.put("中国澳门", "中国澳门");
    }

    /** 省份名归一化：山东省/上海市/内蒙古自治区 → 山东/上海/内蒙古。 */
    private static String shortProvince(String p) {
        if (p == null) return "";
        String s = p.trim();
        if (s.endsWith("省") || s.endsWith("市")) return s.substring(0, s.length() - 1);
        if (s.endsWith("自治区")) return s.substring(0, s.length() - 3);
        for (String k : new String[]{"内蒙古", "广西", "宁夏", "新疆", "西藏", "中国香港", "中国澳门"}) {
            if (s.startsWith(k)) return k;
        }
        return s;
    }

    /**
     * 报价点口径的品种清单（256 个），按报价行数倒序。
     * <p>前端「产品分布」下拉用。同时给出报价点数 / 省份数 / 城市数，便于判断数据量。
     */
    @GetMapping("/points/varieties")
    public Result<Map<String, Object>> pointVarieties() {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");

        // ⛔ 必须排除 varieties_id = 0 —— 那是「全部品种去重」汇总行，不是真实品种
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.varieties_id AS vid, MAX(c.name) AS vname, "
                + "SUM(s.point_cnt) AS points, SUM(s.record_cnt) AS records, "
                + "COUNT(DISTINCT s.province) AS provs, "
                + "COUNT(DISTINCT NULLIF(s.city, '')) AS cities "
                + "FROM enterprise_point_stat s "
                + "LEFT JOIN commodity c ON c.varieties_id = s.varieties_id "
                + "WHERE s.varieties_id > 0 "
                + "GROUP BY s.varieties_id ORDER BY records DESC");

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("varietiesId", num(r.get("vid")));
            m.put("varietiesName", str(r.get("vname")));
            m.put("points", num(r.get("points")));
            m.put("records", lng(r.get("records")));
            m.put("provinces", num(r.get("provs")));
            m.put("cities", num(r.get("cities")));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", list.size());
        out.put("varieties", list);
        return Result.ok(out);
    }

    /**
     * 省级聚合（地图着色）。
     *
     * <p>{@code vid} 为空 = 全部品种，读 {@code varieties_id = 0} 的「去重汇总行」；
     * 指定 vid 则只看该品种。
     *
     * <p>⛔ 为什么需要 0 号行：{@code point_cnt} 是「省×市×品种」粒度的去重点数，
     * 一个报价点报 2 个品种就会有 2 行 —— 跨品种 SUM 会把同一个点重复计 2 次
     * （实测潍坊市：真实 82 个点，SUM 出来 139）。故全部品种场景必须读预先去重的 0 号行。
     * <p>{@code record_cnt} 不受此影响（一条报价必然属于唯一品种，可安全 SUM）。
     */
    @GetMapping("/points/overview")
    public Result<Map<String, Object>> pointOverview(@RequestParam(required = false) String vid) {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");

        int v = 0;
        if (vid != null && !vid.isEmpty()) {
            try { v = Integer.parseInt(vid); } catch (Exception ignore) { }
        }
        final String sql = "SELECT province, SUM(point_cnt) AS points, SUM(record_cnt) AS records, "
                + "COUNT(DISTINCT NULLIF(city, '')) AS cities, MAX(last_date) AS last_date "
                + "FROM enterprise_point_stat WHERE varieties_id = ? "
                + "GROUP BY province ORDER BY records DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, v);

        long totPoints = 0, totRec = 0;
        List<Map<String, Object>> provinces = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String p = str(r.get("province"));
            long pt = lng(r.get("points"));
            long rc = lng(r.get("records"));
            int ct = num(r.get("cities"));
            totPoints += pt; totRec += rc;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("province", p);
            m.put("provinceFull", PROV_FULL_CN.getOrDefault(p, p));
            m.put("points", pt);
            m.put("records", rc);
            m.put("cities", ct);
            m.put("lastDate", r.get("last_date") == null ? null : String.valueOf(r.get("last_date")));
            double[] ll = PROV_LL.get(p);
            m.put("lng", ll != null ? ll[0] : null);
            m.put("lat", ll != null ? ll[1] : null);
            provinces.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("varietiesId", v);
        out.put("totalPoints", totPoints);
        out.put("totalRecords", totRec);
        out.put("totalProvinces", provinces.size());
        out.put("provinces", provinces);
        out.put("updatedAt", jdbcTemplate.queryForObject(
                "SELECT IFNULL(MAX(updated_at), NOW()) FROM enterprise_point", String.class));
        return Result.ok(out);
    }

    /**
     * 市级聚合（点击省份后下钻）。
     *
     * <p>只返回 city 非空的记录 —— 与锚点冲突的报价点在映射阶段已「只到省」，不参与市级。
     * <p>口径与 {@link #pointOverview(String)} 一致：无 vid 读 0 号去重行。
     * <p>返回的 {@code city} 为<b>全称</b>（带「市」后缀），可直接与 {@code cn_city_geo.js} 的地名对齐。
     */
    @GetMapping("/points/cities")
    public Result<Map<String, Object>> pointCities(@RequestParam String province,
                                                   @RequestParam(required = false) String vid) {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");

        String key = shortProvince(province);
        if (key.isEmpty()) return Result.error("缺少省份参数");

        int v = 0;
        if (vid != null && !vid.isEmpty()) {
            try { v = Integer.parseInt(vid); } catch (Exception ignore) { }
        }
        final String sql = "SELECT city, SUM(point_cnt) AS points, SUM(record_cnt) AS records, "
                + "MAX(last_date) AS last_date FROM enterprise_point_stat "
                + "WHERE province = ? AND city <> '' AND varieties_id = ? "
                + "GROUP BY city ORDER BY records DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, key, v);

        long totPoints = 0, totRec = 0;
        List<Map<String, Object>> cities = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long pt = lng(r.get("points"));
            long rc = lng(r.get("records"));
            totPoints += pt; totRec += rc;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("city", str(r.get("city")));
            m.put("points", pt);
            m.put("records", rc);
            m.put("lastDate", r.get("last_date") == null ? null : String.valueOf(r.get("last_date")));
            cities.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("province", key);
        out.put("provinceFull", PROV_FULL_CN.getOrDefault(key, key));
        out.put("varietiesId", v);
        out.put("totalPoints", totPoints);
        out.put("totalRecords", totRec);
        out.put("totalCities", cities.size());
        out.put("cities", cities);
        return Result.ok(out);
    }

    /**
     * 某省（可选某市）+ 可选品种下的报价点明细。
     *
     * <p>⛔ <b>不能读 enterprise_point_stat</b>：它是「省×市×品种」粒度，用它 JOIN 点级记录，
     * 同一城市里每个点都会拿到<b>整个城市的总记录数</b>（实测潍坊 82 个点全显示 167740）。
     * 故这里两步走：① 从 enterprise_point 取点清单（仅 2,288 行，快）；
     * ② 用点名单去 enterprise_price 取各自真实记录数（走 idx_mar_vid_date 前缀）。
     */
    @GetMapping("/points/detail")
    public Result<Map<String, Object>> pointDetail(@RequestParam String province,
                                                   @RequestParam(required = false) String city,
                                                   @RequestParam(required = false) String vid) {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");
        String key = shortProvince(province);
        if (key.isEmpty()) return Result.error("缺少省份参数");

        int v = 0;
        if (vid != null && !vid.isEmpty()) {
            try { v = Integer.parseInt(vid); } catch (Exception ignore) { }
        }

        // ① 该省（可选某市）下的报价点清单
        StringBuilder sql = new StringBuilder(
                "SELECT point_name, city, disp_level, confidence, src, company "
                + "FROM enterprise_point WHERE province = ? AND disp_level <> '不展示' ");
        List<Object> args = new ArrayList<>();
        args.add(key);
        String cityName = city == null ? "" : city.trim();
        if (!cityName.isEmpty()) {
            sql.append("AND city = ? ");
            args.add(cityName);
        }
        sql.append("ORDER BY point_name LIMIT 300");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());

        // ② 各自真实的报价记录数（按报价点聚合）
        Map<String, Map<String, Object>> fact = new HashMap<>();
        if (!rows.isEmpty()) {
            StringBuilder ph = new StringBuilder();
            List<Object> a2 = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) ph.append(',');
                ph.append('?');
                a2.add(str(rows.get(i).get("point_name")));
            }
            StringBuilder s2 = new StringBuilder(
                    "SELECT market_name AS mn, COUNT(*) AS records, MAX(data_date) AS last_date "
                    + "FROM enterprise_price WHERE market_name IN (").append(ph).append(')');
            if (v > 0) {
                s2.append(" AND varieties_id = ?");
                a2.add(v);
            }
            s2.append(" GROUP BY market_name");
            for (Map<String, Object> r : jdbcTemplate.queryForList(s2.toString(), a2.toArray())) {
                fact.put(str(r.get("mn")), r);
            }
        }

        List<Map<String, Object>> points = new ArrayList<>();
        long totRec = 0;
        for (Map<String, Object> r : rows) {
            String nm = str(r.get("point_name"));
            Map<String, Object> f = fact.get(nm);
            long rec = f == null ? 0L : lng(f.get("records"));
            // ⭐ 指定品种时，未报该品种的报价点直接剔除（否则会出现大量「记录 0」的噪音行）
            if (v > 0 && rec == 0L) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", nm);
            m.put("city", str(r.get("city")));
            m.put("dispLevel", str(r.get("disp_level")));
            m.put("confidence", str(r.get("confidence")));
            m.put("src", str(r.get("src")));
            m.put("company", str(r.get("company")));
            m.put("records", rec);
            m.put("lastDate", f == null || f.get("last_date") == null
                    ? null : String.valueOf(f.get("last_date")));
            points.add(m);
            totRec += rec;
        }
        points.sort((a, b) -> Long.compare(lng(b.get("records")), lng(a.get("records"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("province", key);
        out.put("provinceFull", PROV_FULL_CN.getOrDefault(key, key));
        out.put("city", cityName);
        out.put("varietiesId", v);
        out.put("totalPoints", points.size());
        out.put("totalRecords", totRec);
        out.put("points", points);
        return Result.ok(out);
    }

    /**
     * 「省份/城市 × 品种」交叉矩阵（地图页第三屏热力图用）。
     *
     * <p>行 = 报价点最多的 TOP N 个省（或某省内的 TOP N 个市）；
     * 列 = 报价记录最多的 TOP M 个品种；格子值同时给出<b>报价点数</b>与<b>报价记录</b>，
     * 前端可复用页面上的「按报价点数 / 按报价记录」口径开关，无需二次请求。
     *
     * <p>⛔ 行维度必须读 {@code varieties_id = 0} 的「全部品种去重行」排序 ——
     * 直接对 {@code varieties_id > 0} 的行 SUM(point_cnt) 会跨品种重复计点。
     *
     * @param province 传了就是「城市 × 品种」，不传就是「省份 × 品种」
     */
    @GetMapping("/points/matrix")
    public Result<Map<String, Object>> pointMatrix(@RequestParam(required = false) String province,
                                                   @RequestParam(defaultValue = "12") int topRow,
                                                   @RequestParam(defaultValue = "12") int topCol) {
        if (!hasAccess()) return Result.error(403, "该功能需管理员开通");

        int nr = Math.max(3, Math.min(topRow, 20));
        int nc = Math.max(3, Math.min(topCol, 20));
        String key = province == null ? "" : shortProvince(province);
        boolean cityDim = !key.isEmpty();

        // ---------- 1) 行：TOP N 省 / 市（按报价点数，读 0 号去重行） ----------
        List<Map<String, Object>> rowRows = cityDim
                ? jdbcTemplate.queryForList(
                        "SELECT city AS label, SUM(point_cnt) AS pts, SUM(record_cnt) AS recs "
                        + "FROM enterprise_point_stat WHERE province = ? AND city <> '' AND varieties_id = 0 "
                        + "GROUP BY city ORDER BY pts DESC LIMIT " + nr, key)
                : jdbcTemplate.queryForList(
                        "SELECT province AS label, SUM(point_cnt) AS pts, SUM(record_cnt) AS recs "
                        + "FROM enterprise_point_stat WHERE varieties_id = 0 "
                        + "GROUP BY province ORDER BY pts DESC LIMIT " + nr);

        List<String> rows = new ArrayList<>();
        List<Long> rowTotals = new ArrayList<>();
        for (Map<String, Object> r : rowRows) {
            rows.add(str(r.get("label")));
            rowTotals.add(lng(r.get("pts")));
        }
        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("dim", cityDim ? "city" : "province");
            empty.put("rows", rows);
            empty.put("cols", new ArrayList<>());
            empty.put("cells", new ArrayList<>());
            return Result.ok(empty);
        }

        // ---------- 2) 列：TOP M 品种（按报价记录） ----------
        List<Map<String, Object>> colRows = jdbcTemplate.queryForList(
                "SELECT s.varieties_id AS vid, MAX(c.name) AS vname, SUM(s.record_cnt) AS recs "
                + "FROM enterprise_point_stat s LEFT JOIN commodity c ON c.varieties_id = s.varieties_id "
                + "WHERE s.varieties_id > 0 GROUP BY s.varieties_id "
                + "ORDER BY recs DESC LIMIT " + nc);

        List<Map<String, Object>> cols = new ArrayList<>();
        List<Integer> colVids = new ArrayList<>();
        for (Map<String, Object> r : colRows) {
            int vid = num(r.get("vid"));
            String vname = str(r.get("vname"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("varietiesId", vid);
            m.put("name", vname.isEmpty() ? ("#" + vid) : vname);
            cols.add(m);
            colVids.add(vid);
        }

        // ---------- 3) 格子（只查命中行列的，稀疏返回） ----------
        String rowField = cityDim ? "s.city" : "s.province";
        StringBuilder sql = new StringBuilder(
                "SELECT " + rowField + " AS r, s.varieties_id AS v, "
                + "SUM(s.point_cnt) AS pts, SUM(s.record_cnt) AS recs "
                + "FROM enterprise_point_stat s WHERE s.varieties_id > 0 ");
        List<Object> args = new ArrayList<>();
        if (cityDim) {
            sql.append("AND s.province = ? AND s.city <> '' ");
            args.add(key);
        }
        appendIn(sql, args, rowField, rows);
        appendIn(sql, args, "s.varieties_id", colVids);
        sql.append("GROUP BY r, v");

        Map<String, Integer> rowIdx = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) rowIdx.put(rows.get(i), i);
        Map<Integer, Integer> colIdx = new HashMap<>();
        for (int i = 0; i < colVids.size(); i++) colIdx.put(colVids.get(i), i);

        List<List<Object>> cells = new ArrayList<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            Integer ri = rowIdx.get(str(r.get("r")));
            Integer ci = colIdx.get(num(r.get("v")));
            if (ri == null || ci == null) continue;
            List<Object> c = new ArrayList<>(4);
            c.add(ri);
            c.add(ci);
            c.add(lng(r.get("pts")));
            c.add(lng(r.get("recs")));
            cells.add(c);
        }
        cells.sort((a, b) -> {
            int c = Integer.compare((Integer) a.get(0), (Integer) b.get(0));
            return c != 0 ? c : Integer.compare((Integer) a.get(1), (Integer) b.get(1));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dim", cityDim ? "city" : "province");
        out.put("scopeName", cityDim ? PROV_FULL_CN.getOrDefault(key, key) : "全国");
        out.put("rows", rows);
        out.put("rowTotals", rowTotals);
        out.put("cols", cols);
        out.put("cells", cells);
        return Result.ok(out);
    }

    /** 拼 {@code field IN (?,?,…)} 并追加参数（列表为空时拼一个恒假条件，避免语法错误）。 */
    private static void appendIn(StringBuilder sql, List<Object> args, String field, List<?> values) {
        if (values == null || values.isEmpty()) {
            sql.append("AND 1 = 0 ");
            return;
        }
        sql.append("AND ").append(field).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
            args.add(values.get(i));
        }
        sql.append(") ");
    }

    // ---------- 内部 ----------

    /**
     * 标点地图授权状态：开关 + 到期日 + 是否已过期 + 剩余天数。
     * 到期日语义与 data_permission.expire_date 保持一致：**NULL = 永久，到期日当天仍有效**。
     */
    private Map<String, Object> emapAuth(Long userId) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("switchOn", false);
        o.put("enabled", false);
        o.put("expireDate", null);
        o.put("daysLeft", null);
        o.put("expired", false);
        if (userId == null) return o;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT IFNULL(enterprise_map_enabled,0) AS en, enterprise_map_expire_date AS ed "
                + "FROM sys_user WHERE id = ?", userId);
        if (rows.isEmpty()) return o;
        Map<String, Object> r = rows.get(0);
        int en = r.get("en") instanceof Number n ? n.intValue() : 0;
        LocalDate d = toLocalDate(r.get("ed"));
        boolean expired = d != null && d.isBefore(LocalDate.now());
        o.put("switchOn", en == 1);
        o.put("enabled", en == 1 && !expired);
        o.put("expireDate", d == null ? null : d.toString());
        o.put("daysLeft", d == null ? null : (int) ChronoUnit.DAYS.between(LocalDate.now(), d));
        o.put("expired", en == 1 && expired);
        return o;
    }

    /** 到期日是否有效（未过期）。仅看日期；开关状态由调用方判断。 */
    private boolean isEnabled(Long userId) {
        return Boolean.TRUE.equals(emapAuth(userId).get("enabled"));
    }

    /** 兼容 java.sql.Date / Timestamp / String 三种取值形态。 */
    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof java.sql.Timestamp tst) return tst.toLocalDateTime().toLocalDate();
        if (o instanceof LocalDate ld) return ld;
        String t = String.valueOf(o).trim();
        if (t.length() > 10) t = t.substring(0, 10);
        try { return LocalDate.parse(t); } catch (Exception e) { return null; }
    }

    private boolean hasAccess() {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null) return false;
        if ("ADMIN".equals(u.getRole())) return true;
        return isEnabled(u.getId());
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** long 版：报价记录数可达百万级，用 int 会溢出风险（各接口统计口径统一用 long）。 */
    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
