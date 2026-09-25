package com.datamarket.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datamarket.common.Result;
import com.datamarket.entity.Commodity;
import com.datamarket.mapper.CommodityMapper;
import com.datamarket.entity.SysUser;
import com.datamarket.mapper.SysUserMapper;
import com.datamarket.security.PermissionHelper;
import com.datamarket.service.AuditService;
import com.datamarket.service.PriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PriceController {

    private final PriceService priceService;
    private final CommodityMapper commodityMapper;
    private final PermissionHelper permissionHelper;
    private final JdbcTemplate jdbcTemplate;
    private final SysUserMapper userMapper;
    private final AuditService auditService;

    /**
     * 获取商品列表，普通用户只看授权商品
     * @param all true=返回全部商品（申请页面用），忽略权限过滤
     */
    @GetMapping("/commodities")
    public Result<List<Commodity>> getCommodities(
            @RequestParam(required = false, defaultValue = "false") boolean all) {
        List<Commodity> list = commodityMapper.selectList(
            new QueryWrapper<Commodity>().eq("status", 1).orderByAsc("varieties_id"));

        // all=true 时跳过权限过滤（申请页面需要看到全部商品）
        if (!all) {
            List<Integer> permitted = permissionHelper.getPermittedVarietiesIds();
            if (permitted != null) { // null=ADMIN, 不过滤
                list = list.stream()
                    .filter(c -> permitted.contains(c.getVarietiesId()))
                    .collect(Collectors.toList());
            }
        }
        return Result.ok(list);
    }

    @GetMapping("/dashboard/stats")
    public Result<Map<String, Object>> getStats() {
        // 按当前用户角色和授权过滤（D4 高：原实现硬编码 ADMIN，导致普通用户看到全平台统计）
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : "";
        String role = "USER";
        List<Integer> permittedIds = permissionHelper.getPermittedVarietiesIds(); // null=ADMIN
        if (permittedIds == null) {
            role = "ADMIN";
        } else if (permittedIds.isEmpty()) {
            // 零授权用户：直接返回全 0，避免空 IN () 导致 SQL 语法错误
            Map<String, Object> zero = new java.util.LinkedHashMap<>();
            zero.put("totalCount", 0);
            zero.put("varietiesCount", 0);
            zero.put("latestDate", null);
            zero.put("marketCount", 0);
            zero.put("enterpriseCount", 0);
            zero.put("intlCount", 0);
            return Result.ok(zero);
        }
        return Result.ok(priceService.getStats(username, role, permittedIds));
    }

    @GetMapping("/dashboard/augstats")
    public Result<Map<String, Object>> getAugStats() {
        // 与 dashboard/stats 一致：按当前用户角色与授权过滤（今日涨跌只统计授权品种）
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : "";
        String role = "USER";
        List<Integer> permittedIds = permissionHelper.getPermittedVarietiesIds(); // null=ADMIN
        if (permittedIds == null) {
            role = "ADMIN";
        } else if (permittedIds.isEmpty()) {
            // 零授权用户：直接返回全 0，避免空 IN () 导致 SQL 语法错误
            Map<String, Object> zero = new java.util.LinkedHashMap<>();
            zero.put("totalCount", 0);
            zero.put("upCount", 0);
            zero.put("flatCount", 0);
            zero.put("downCount", 0);
            return Result.ok(zero);
        }
        return Result.ok(priceService.getAugStats(username, role, permittedIds));
    }

    @GetMapping("/dashboard/trend")
    public Result<List<Map<String, Object>>> getTrend(
            @RequestParam Integer varietiesId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "market") String tableType) {
        if (!permissionHelper.canAccess(varietiesId)) {
            return Result.error("无权访问该商品数据");
        }
        return Result.ok(priceService.getTrend(varietiesId, startDate, endDate, tableType));
    }

    @GetMapping("/dashboard/movers")
    public Result<List<Map<String, Object>>> getMovers(
            @RequestParam(defaultValue = "up") String type,
            @RequestParam(defaultValue = "10") Integer limit,
            @RequestParam(defaultValue = "market") String tableType) {
        // 先拉更多数据（50条），过滤权限后再截取 limit 条，避免授权品种涨幅不在TOP10时返回空
        int fetchLimit = Math.max(limit * 5, 50);
        List<Map<String, Object>> movers = priceService.getMoversTop(type, fetchLimit, tableType);
        List<Integer> permitted = permissionHelper.getPermittedVarietiesIds();
        if (permitted != null) {
            movers = movers.stream()
                .filter(m -> {
                    Object vid = m.get("varieties_id");
                    return vid != null && permitted.contains(((Number) vid).intValue());
                })
                .collect(Collectors.toList());
        }
        // 截取实际请求数量
        if (movers.size() > limit) {
            movers = movers.subList(0, limit);
        }
        return Result.ok(movers);
    }

    /**
     * 价格查询，自动追加 varieties_id 权限过滤
     */
    /**
     * 报价点联动选项：按「价格类型 + 商品 + 日期范围」返回实际存在的报价点，
     * 供数据查询页做级联筛选（商品/类型/日期变化 → 报价点列表刷新）。
     */
    @GetMapping("/prices/market-options")
    public Result<List<String>> getMarketOptions(
            @RequestParam(defaultValue = "") String tableType,
            @RequestParam(required = false) String varietiesId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        List<Integer> permitted = permissionHelper.getPermittedVarietiesIds();
        if (permitted != null && permitted.isEmpty()) {
            return Result.ok(List.of());
        }

        List<Integer> varietiesIds = null;
        if (varietiesId != null && !varietiesId.isBlank()) {
            try {
                varietiesIds = java.util.Arrays.stream(varietiesId.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Integer::parseInt).collect(Collectors.toList());
            } catch (NumberFormatException e) {
                return Result.error("varietiesId 参数格式错误");
            }
            if (permitted != null) {
                for (Integer vid : varietiesIds) {
                    if (!permitted.contains(vid)) {
                        return Result.error("无权访问品种 " + vid);
                    }
                }
            }
        } else if (permitted != null) {
            varietiesIds = permitted;
        }

        return Result.ok(priceService.getMarketNames(tableType, varietiesIds, startDate, endDate));
    }

    @GetMapping("/prices")
    public Result<Map<String, Object>> getMarketPrices(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String tableType,
            @RequestParam(required = false) String varietiesId,
            @RequestParam(required = false) String marketName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        // 权限过滤：普通用户只能查授权商品
        List<Integer> permitted = permissionHelper.getPermittedVarietiesIds();
        if (permitted != null && permitted.isEmpty()) {
            // 零授权用户
            return Result.ok(Map.of("data", List.of(), "total", 0, "page", page, "size", size));
        }

        // 构造 varietiesId 列表（支持多品种 IN 查询）
        // null=ADMIN 不过滤；非空=限定品种集合
        List<Integer> varietiesIds = null;
        if (varietiesId != null && !varietiesId.isBlank()) {
            // 解析逗号分隔的字符串（如 "146,336"）
            try {
                varietiesIds = java.util.Arrays.stream(varietiesId.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Integer::parseInt).collect(Collectors.toList());
            } catch (NumberFormatException e) {
                return Result.error("varietiesId 参数格式错误");
            }
            // 校验每个 varietiesId 都有权限
            if (permitted != null) {
                for (Integer vid : varietiesIds) {
                    if (!permitted.contains(vid)) {
                        return Result.error("无权访问品种 " + vid);
                    }
                }
            }
        } else if (permitted != null) {
            // 普通用户未指定 varietiesId 时，限定为授权品种集合
            varietiesIds = permitted;
        }

        Map<String, Object> result = priceService.getMarketPrices(
                varietiesIds, tableType, marketName, startDate, endDate, page, size);

        // 国际价格行：补当日汇率 + 人民币折算（fx_rate 表每日更新）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
        if (rows != null && !rows.isEmpty()) {
            boolean hasIntl = rows.stream().anyMatch(r -> {
                Object bt = r.get("business_type");
                return bt != null && ((Number) bt).intValue() == 4;
            });
            if (hasIntl) enrichIntlFx(rows);
        }

        return Result.ok(result);
    }

    /** 是否"xx/吨"口径（仅吨价做汇率折算；美分/磅、美分/加仑等体积/重量口径不折算，避免错误） */
    private boolean isTonUnit(String unit) {
        if (unit == null) return false;
        String u = unit.trim();
        if (u.contains("美分")) return false;   // 美分/磅、美分/加仑 不做折算
        return u.contains("吨") || u.contains("元/吨");
    }

    /** 从单位/字段推断 ISO 币种（与爬虫口径一致） */
    private String detectCurrency(Map<String, Object> row) {
        Object unitObj = row.get("unit_valuation_name");
        Object fxObj = row.get("fx_used_currency");
        String unit = unitObj != null ? String.valueOf(unitObj) : "";
        String fx = fxObj != null ? String.valueOf(fxObj) : "";
        if (unit.contains("美元") || unit.contains("USD")) return "USD";
        if (unit.contains("美分")) return "USD";          // 美分仍属美元，仅用于标识
        if (unit.contains("欧元") || unit.contains("EUR")) return "EUR";
        if (unit.contains("英镑") || unit.contains("GBP")) return "GBP";
        if (unit.contains("日元") || unit.contains("JPY")) return "JPY";
        if (unit.contains("港币") || unit.contains("HKD")) return "HKD";
        if (unit.contains("新加坡元") || unit.contains("SGD")) return "SGD";
        if (unit.contains("人民币") || unit.contains("元/吨")) return "CNY";
        // 单位缺失时退回行内 fx_used_currency
        if (fx != null && !fx.isBlank()) return fx.toUpperCase();
        return null;
    }

    /** 为国际价格行动态补充 fx_used_currency / fx_rate_to_cny / fx_label / calc_rmb_price */
    private void enrichIntlFx(List<Map<String, Object>> rows) {
        // 一次载入全部汇率（fx_rate ~6.6k 行，内存建索引即可）
        List<Map<String, Object>> fxAll = jdbcTemplate.queryForList(
            "SELECT currency, rate_date, rate_to_cny FROM fx_rate ORDER BY currency, rate_date");
        java.util.Map<String, java.util.List<java.sql.Date>> datesByCur = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<Double>> rateByCur = new java.util.HashMap<>();
        for (Map<String, Object> fx : fxAll) {
            String cur = String.valueOf(fx.get("currency"));
            Object rd = fx.get("rate_date");
            Object rc = fx.get("rate_to_cny");
            if (cur == null || rd == null || rc == null) continue;
            java.sql.Date d = (java.sql.Date) rd;
            double r = ((Number) rc).doubleValue();
            datesByCur.computeIfAbsent(cur, k -> new java.util.ArrayList<>()).add(d);
            rateByCur.computeIfAbsent(cur, k -> new java.util.ArrayList<>()).add(r);
        }

        for (Map<String, Object> row : rows) {
            Object bt = row.get("business_type");
            if (bt == null || ((Number) bt).intValue() != 4) continue;   // 仅国际行富化
            String iso = detectCurrency(row);
            row.put("fx_used_currency", iso != null ? iso : row.get("fx_used_currency"));
            if (iso == null || "CNY".equals(iso)) {
                row.put("fx_rate_to_cny", null);
                row.put("fx_label", null);
                continue;
            }
            Object dobj = row.get("data_date");
            java.sql.Date d = dobj instanceof java.sql.Date ? (java.sql.Date) dobj
                : java.sql.Date.valueOf(String.valueOf(dobj).substring(0, 10));
            // 找 <= d 的最近一条汇率（二分）
            Double rate = null;
            java.util.List<java.sql.Date> ds = datesByCur.get(iso);
            java.util.List<Double> rs = rateByCur.get(iso);
            if (ds != null && !ds.isEmpty()) {
                int lo = 0, hi = ds.size() - 1, ans = -1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    if (!ds.get(mid).after(d)) { ans = mid; lo = mid + 1; }
                    else hi = mid - 1;
                }
                if (ans >= 0) rate = rs.get(ans);
            }
            row.put("fx_rate_to_cny", rate);
            row.put("fx_label", rate != null ? iso + "/CNY ≈ " + String.format(java.util.Locale.US, "%.4f", rate) : null);
            // 人民币价：库内已有优先（旧爬虫折算值/接口人民币价），缺失且为吨价口径则动态折算
            Object calc = row.get("calc_rmb_price");
            if (calc == null) calc = row.get("rmb_price");
            if (calc == null && rate != null && isTonUnit(String.valueOf(row.get("unit_valuation_name")))) {
                Object mid = row.get("middle_price");
                if (mid != null) {
                    calc = Math.round(((Number) mid).doubleValue() * rate * 100.0) / 100.0;
                }
            }
            row.put("calc_rmb_price", calc);
        }
    }

    @GetMapping("/prices/matrix")
    public Result<Map<String, Object>> getPriceMatrix(
            @RequestParam Integer varietiesId,
            @RequestParam(defaultValue = "market") String tableType,
            @RequestParam(defaultValue = "2026-08-24") String startDate,
            @RequestParam(defaultValue = "2026-08-28") String endDate) {
        if (!permissionHelper.canAccess(varietiesId)) {
            return Result.error("无权访问该商品数据");
        }
        return Result.ok(priceService.getPriceMatrix(varietiesId, tableType, startDate, endDate));
    }

    /**
     * 报价点分析：给定商品+报价点+价格类型，返回多维分析数据
     */
    @GetMapping("/prices/analysis")
    public Result<Map<String, Object>> getAnalysis(
            @RequestParam Integer varietiesId,
            @RequestParam String marketName,
            @RequestParam(defaultValue = "market") String tableType) {

        // 权限校验（D1 严重：未授权用户能读取未授权商品的最新价/6月统计/趋势/同行对比）
        if (!permissionHelper.canAccess(varietiesId)) {
            return Result.error("无权访问该商品数据");
        }

        String tbl = switch (tableType) {
            case "enterprise" -> "enterprise_price";
            case "international" -> "international_price";
            default -> "market_price";
        };

        Map<String, Object> result = new java.util.LinkedHashMap<>();

        // 1. 最新一条
        List<Map<String, Object>> latest = jdbcTemplate.queryForList(
            "SELECT * FROM " + tbl + " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL ORDER BY data_date DESC LIMIT 1",
            varietiesId, marketName);
        result.put("latest", latest.isEmpty() ? null : latest.get(0));

        // 2. 统计概览（近30天）
        List<Map<String, Object>> stats = jdbcTemplate.queryForList(
            "SELECT MIN(middle_price) AS minPrice, ROUND(AVG(middle_price),0) AS avgPrice, " +
            "MAX(middle_price) AS maxPrice, COUNT(*) AS totalDays, " +
            "ROUND(STDDEV(middle_price),0) AS stdDev " +
            "FROM " + tbl + " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL " +
            "AND data_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)",
            varietiesId, marketName);
        result.put("stats", stats.isEmpty() ? null : stats.get(0));

        // 3. 近12月月度趋势（均价/最高/最低）
        List<Map<String, Object>> monthly = jdbcTemplate.queryForList(
            "SELECT DATE_FORMAT(data_date, '%Y-%m') AS month, " +
            "ROUND(AVG(middle_price),0) AS avgPrice, " +
            "ROUND(MAX(middle_price),0) AS highPrice, " +
            "ROUND(MIN(middle_price),0) AS lowPrice, " +
            "ROUND(MAX(middle_price)-MIN(middle_price),0) AS spread " +
            "FROM " + tbl + " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL " +
            "AND data_date >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH) " +
            "GROUP BY month ORDER BY month ASC",
            varietiesId, marketName);
        result.put("monthlyTrend", monthly);

        // 4. 近30天日度走势
        List<Map<String, Object>> daily = jdbcTemplate.queryForList(
            "SELECT data_date AS dt, middle_price AS price, high_price, low_price, data_rise_or_fall AS changeAmt " +
            "FROM " + tbl + " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL " +
            "AND data_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) ORDER BY data_date ASC",
            varietiesId, marketName);
        result.put("dailyTrend", daily);

        // 5. 涨跌频率（近30天）
        List<Map<String, Object>> riseFall = jdbcTemplate.queryForList(
            "SELECT CASE WHEN data_rise_or_fall > 0 THEN 'up' WHEN data_rise_or_fall < 0 THEN 'down' ELSE 'flat' END AS dir, " +
            "COUNT(*) AS cnt, ROUND(AVG(data_rise_or_fall),2) AS avgChg " +
            "FROM " + tbl + " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL " +
            "AND data_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) GROUP BY dir",
            varietiesId, marketName);
        result.put("riseFall", riseFall);

        // 5. 同商品其他报价点均价对比（近30天）
        List<Map<String, Object>> peers = jdbcTemplate.queryForList(
            "SELECT market_name, ROUND(AVG(middle_price),0) AS avgPrice, COUNT(*) AS days " +
            "FROM " + tbl + " WHERE varieties_id=? AND middle_price IS NOT NULL " +
            "AND data_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) " +
            "GROUP BY market_name ORDER BY avgPrice DESC LIMIT 200",
            varietiesId);
        result.put("peers", peers);

        return Result.ok(result);
    }

    /**
     * 导出价格数据为 Excel
     */
    @GetMapping("/prices/export")
    public void exportPrices(
            @RequestParam(defaultValue = "") String tableType,
            @RequestParam(required = false) String varietiesId,
            @RequestParam(required = false) String exportMarketName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            Authentication authentication,
            HttpServletResponse response) throws Exception {

        // 导出权限校验
        if (!canExport(authentication)) {
            auditService.warn("EXPORT_PRICE_DENIED", "DATA", "EXPORT", varietiesId, null,
                "{\"tableType\":\"" + tableType + "\",\"varietiesId\":\"" + (varietiesId != null ? varietiesId : "") + "\"}",
                "导出被拒：无数据导出权限");
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"没有数据导出权限\"}");
            return;
        }

        String tbl = switch (tableType) {
            case "enterprise" -> "enterprise_price";
            case "international" -> "international_price";
            default -> "market_price";
        };

        StringBuilder sql = new StringBuilder(
            "SELECT varieties_name, market_name, specifications_name, " +
            "middle_price, low_price, high_price, " +
            "unit_valuation_name, data_rise_or_fall, data_rate, " +
            "data_date FROM " + tbl + " WHERE middle_price IS NOT NULL");

        if (varietiesId != null && !varietiesId.isBlank()) {
            sql.append(" AND varieties_id = ").append(varietiesId);
        }
        if (exportMarketName != null && !exportMarketName.isBlank()) {
            sql.append(" AND market_name = '").append(exportMarketName.replace("'", "''")).append("'");
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND data_date >= '").append(startDate).append("'");
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND data_date <= '").append(endDate).append("'");
        }
        sql.append(" ORDER BY data_date DESC, varieties_id LIMIT 10000");

        List<Map<String, Object>> data = jdbcTemplate.queryForList(sql.toString());

        String typeName = switch (tableType) {
            case "enterprise" -> "企业价格";
            case "international" -> "国际价格";
            default -> "市场价格";
        };

        // 返回 JSON 数组，前端负责生成 Excel
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Content-Disposition",
            "attachment;filename=" + URLEncoder.encode(typeName + "数据导出.json", StandardCharsets.UTF_8));

        String[] cols = {"varieties_name", "market_name", "specifications_name",
                "middle_price", "low_price", "high_price",
                "unit_valuation_name", "data_rise_or_fall", "data_rate", "data_date"};
        String[] headers = {"商品", "报价点", "规格", "主流价", "最低价", "最高价", "单位", "涨跌额", "涨跌幅", "数据日期"};

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            for (int j = 0; j < cols.length; j++) {
                if (j > 0) json.append(",");
                Object val = row.get(cols[j]);
                String s = val != null ? val.toString().replace("\\", "\\\\").replace("\"", "\\\"") : "";
                json.append("\"").append(headers[j]).append("\":\"").append(s).append("\"");
            }
            json.append("}");
        }
        json.append("]");

        // 导出成功审计
        auditService.ok("EXPORT_PRICE", "DATA", "EXPORT", varietiesId, null,
            "{\"tableType\":\"" + tableType + "\",\"varietiesId\":\"" + (varietiesId != null ? varietiesId : "")
                + "\",\"startDate\":\"" + (startDate != null ? startDate : "") + "\",\"endDate\":\""
                + (endDate != null ? endDate : "") + "\",\"rows\":" + data.size() + "}",
            "导出" + typeName + "数据 " + data.size() + " 条");

        response.getOutputStream().write(json.toString().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private boolean canExport(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        SysUser user = userMapper.selectOne(
            new QueryWrapper<SysUser>().eq("username", auth.getName()));
        if (user == null) return false;
        // ADMIN 默认有导出权限；USER 需要 export_permission = 1
        return "ADMIN".equals(user.getRole()) || Integer.valueOf(1).equals(user.getExportPermission());
    }
}
