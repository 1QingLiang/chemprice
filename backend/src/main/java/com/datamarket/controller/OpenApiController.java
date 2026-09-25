package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.mapper.OpenApiMapper;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ChemPrice 开放数据 API（只读，/api/open/v1/**）。
 * 鉴权由 OpenApiKeyFilter 负责（X-API-Key），与站内 JWT 体系完全隔离；
 * 这里不做任何用户权限过滤 —— 开放 API 的定位是「公开行情数据的订阅接口」。
 *
 * 请求方式：POST（推荐，JSON body 传参，中文无需 URL 编码）；同时兼容 GET（query 传参，老调用平滑迁移）。
 * 数据口径与站内一致：主流价 = (low+high)/2 中点价；涨跌 = 相邻交易日中间价差。
 * 行情查询为单日语义（不传 date = 最新交易日）。
 */
@RestController
@RequestMapping("/api/open/v1")
public class OpenApiController {

    private final OpenApiMapper mapper;

    private static final Pattern DATE_P = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public OpenApiController(OpenApiMapper mapper) {
        this.mapper = mapper;
    }

    /** 品种搜索/列表（上架品种） */
    @RequestMapping(value = "/commodities", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<List<Map<String, Object>>> commodities(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestBody(required = false) Map<String, Object> body) {
        String qq = firstStr(body, "q", q);
        Integer lim = firstInt(body, "limit", limit);
        return Result.ok(mapper.searchCommodities(qq == null ? "" : qq.trim(), clamp(lim, 1, 200, 50)));
    }

    /**
     * 品种详情：返回该品种可用的筛选维度（报价点、业务类型、价格类型、规格）。
     * 用 varietiesId 或 name 查；调用流程：先 /commodities 拿 ID → 再 /commodities/detail 拿维度 → 最后 /prices/latest 带过滤查行情。
     */
    @RequestMapping(value = "/commodities/detail", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<Map<String, Object>> commodityDetail(
            @RequestParam(required = false) Integer varietiesId,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "500") Integer limit,
            @RequestBody(required = false) Map<String, Object> body) {
        Integer vidB = firstIntObj(body, "varietiesId");
        int vid = vidB != null ? vidB
                : (varietiesId != null ? varietiesId : resolveVarietiesId(firstStr(body, "name", name)));
        if (vid <= 0) {
            return Result.error(400, "请提供 varietiesId 或 name 参数");
        }
        Integer limB = firstIntObj(body, "limit");
        int lim = clamp(limB != null ? limB : limit, 1, 2000, 500);

        // 查品种基本信息
        Map<String, Object> info = mapper.findCommodity(vid);
        if (info == null) {
            return Result.error(400, "品种不存在或已下架（varietiesId=" + vid + "）");
        }

        // 查维度
        List<Map<String, Object>> dims = mapper.detailDimensions(vid, lim);

        // 聚合成三个列表 + 报价点列表
        Set<String> bts = new LinkedHashSet<>();
        Set<String> pts = new LinkedHashSet<>();
        Set<String> specs = new LinkedHashSet<>();
        List<Map<String, Object>> markets = new ArrayList<>();
        for (Map<String, Object> r : dims) {
            String bt = str(r.get("businessType"));
            String pt = str(r.get("priceType"));
            String sp = str(r.get("specification"));
            if (bt != null) bts.add(bt);
            if (pt != null) pts.add(pt);
            if (sp != null) specs.add(sp);
            markets.add(Map.of(
                    "market", Objects.toString(r.get("market"), ""),
                    "region", Objects.toString(r.get("region"), ""),
                    "businessType", Objects.toString(r.get("businessType"), ""),
                    "priceType", Objects.toString(r.get("priceType"), ""),
                    "specification", Objects.toString(r.get("specification"), "")
            ));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("varietiesId", vid);
        out.put("name", info.get("name"));
        out.put("category", info.get("category"));
        out.put("unit", info.get("unit"));
        out.put("businessTypes", new ArrayList<>(bts));
        out.put("priceTypes", new ArrayList<>(pts));
        out.put("specifications", new ArrayList<>(specs));
        out.put("markets", markets);
        return Result.ok(out);
    }

    /**
     * 某品种行情。时间语义（二选一）：
     * - 不传 date：返回最新交易日
     * - date=yyyy-MM-dd：返回指定交易日（无数据则返回空 quotes）
     * 可选过滤：businessType（市场价格/企业价格/国际价格）、priceType（厂提现汇/送到现汇...）、market（报价点名）。
     * 不传过滤参数 = 返回全部（向后兼容）。先用 /commodities/detail 查可选值。
     * 注：2026-09-21 起取消日期区间查询，只保留单日；历史多日请按日循环调用。
     */
    @RequestMapping(value = "/prices/latest", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<Map<String, Object>> latest(
            @RequestParam(required = false) Integer varietiesId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) String priceType,
            @RequestParam(required = false) String market,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestBody(required = false) Map<String, Object> body) {
        Integer vidB = firstIntObj(body, "varietiesId");
        int vid = vidB != null ? vidB
                : (varietiesId != null ? varietiesId : resolveVarietiesId(firstStr(body, "name", name)));
        if (vid <= 0) {
            return Result.error(400, "请提供 varietiesId 或 name 参数（name 按品种名模糊匹配）");
        }
        String d = norm(firstStr(body, "date", date));
        String bt = norm(firstStr(body, "businessType", businessType));
        String pt = norm(firstStr(body, "priceType", priceType));
        String mkt = norm(firstStr(body, "market", market));
        Integer limB = firstIntObj(body, "limit");
        int lim = clamp(limB != null ? limB : limit, 1, 500, 50);

        if (d != null && !DATE_P.matcher(d).matches()) {
            return Result.error(400, "date 格式应为 yyyy-MM-dd");
        }
        if (d == null) {
            d = mapper.latestDate();          // 默认：最新交易日
        }
        long total = mapper.countQuotes(vid, d, bt, pt, mkt);
        List<Map<String, Object>> rows = mapper.quotesOn(vid, d, bt, pt, mkt, lim);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("varietiesId", vid);
        out.put("date", d);
        if (bt != null) out.put("businessType", bt);
        if (pt != null) out.put("priceType", pt);
        if (mkt != null) out.put("market", mkt);
        out.put("total", total);                         // 匹配总记录数（未受 limit 截断）
        out.put("count", rows.size());                   // 本次实际返回条数
        out.put("quotes", rows);
        return Result.ok(out);
    }

    /** 能力说明（无需参数即可自描述） */
    @RequestMapping(value = "/meta", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<Map<String, Object>> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "ChemPrice Open API");
        m.put("version", "v1");
        m.put("method", "POST（推荐，JSON body 传参）；兼容 GET query 传参");
        m.put("auth", "请求头 X-API-Key（在平台「开放 API」页面生成；明文只在生成时显示一次）");
        m.put("endpoints", List.of(
                "POST /api/open/v1/commodities          body: {\"q\":\"甲醇\",\"limit\":10}",
                "POST /api/open/v1/commodities/detail   body: {\"varietiesId\":146}  → 可用报价点/业务类型/价格类型/规格",
                "POST /api/open/v1/prices/latest        body: {\"varietiesId\":146} 或 {\"name\":\"甲醇\",\"date\":\"2026-09-10\"}",
                "POST /api/open/v1/meta"));
        m.put("errors", Map.of(
                400, "参数缺失或格式错误",
                401, "API Key 缺失/无效/已停用",
                429, "超出每日调用额度"));
        return Result.ok(m);
    }

    /* ---- body 与 query 的合并：body 优先 ---- */

    private static String firstStr(Map<String, Object> body, String key, String dft) {
        if (body == null) {
            return dft;
        }
        Object v = body.get(key);
        return v == null ? dft : String.valueOf(v);
    }

    private static Integer firstInt(Map<String, Object> body, String key, Integer dft) {
        Integer v = firstIntObj(body, key);
        return v == null ? dft : v;
    }

    private static Integer firstIntObj(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && s.matches("\\d+")) {
            return Integer.valueOf(s);
        }
        return null;
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private int resolveVarietiesId(String name) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        List<Map<String, Object>> hits = mapper.searchCommodities(name.trim(), 1);
        if (hits.isEmpty()) {
            return -1;
        }
        return ((Number) hits.get(0).get("varietiesId")).intValue();
    }

    private int clamp(Integer v, int min, int max, int dft) {
        if (v == null) {
            return dft;
        }
        return Math.max(min, Math.min(max, v));
    }
}
