package com.datamarket.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 受控查询层：全部工具查询在这里以「参数化 SQL + data_permission 子句」执行。
 * <p>
 * 安全设计：
 * 1. 所有 SQL 均为服务端模板，LLM 永远不接触 SQL / 连接；
 * 2. 每个查询尾部拼接权限子句（普通用户限定本人未过期授权品种，管理员不加）；
 * 3. 排序/表名均来自服务端白名单映射，杜绝注入；
 * 4. 结果行数一律设上限，防止大结果集打爆 LLM 上下文。
 */
@Service
@Slf4j
public class AiQueryService {

    private final JdbcTemplate jdbcTemplate;

    public AiQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 把用户口述的报价点名称对齐到库里真实存在的 market_name。
     * <p>用户常说「山东」「华东」，而库里可能叫「山东中部」「华东地区」。此前各查询都是
     * {@code market_name=?} 精确匹配，名字对不上会直接查不到数据、被误答成「暂无收录」。
     * <p>规则：精确命中 → 原样返回；模糊命中 1 个 → 用该名；命中多个 → 取数据行数最多的那个；
     * 一个都没命中 → 原样返回（保持原有"查不到"的行为，不改变语义）。
     */
    private String resolveMarketName(String tbl, int varietiesId, String keyword) {
        if (keyword == null || keyword.isBlank()) return keyword;
        String kw = keyword.trim();
        try {
            List<Map<String, Object>> exact = jdbcTemplate.queryForList(
                    "SELECT market_name FROM " + tbl +
                            " WHERE varieties_id=? AND market_name=? AND middle_price >= 0" +
                            " AND data_date >= DATE_SUB(CURDATE(), INTERVAL 180 DAY) LIMIT 1",
                    varietiesId, kw);
            if (!exact.isEmpty()) return kw;

            List<Map<String, Object>> like = jdbcTemplate.queryForList(
                    "SELECT market_name, COUNT(*) n FROM " + tbl +
                            " WHERE varieties_id=? AND market_name LIKE ? AND middle_price >= 0" +
                            " AND data_date >= DATE_SUB(CURDATE(), INTERVAL 180 DAY)" +
                            " GROUP BY market_name ORDER BY n DESC LIMIT 1",
                    varietiesId, "%" + kw + "%");
            if (!like.isEmpty()) {
                String hit = String.valueOf(like.get(0).get("market_name"));
                log.info("报价点对齐：品种 {} 的「{}」-> 「{}」", varietiesId, kw, hit);
                return hit;
            }
        } catch (Exception e) {
            log.warn("报价点对齐失败，按原始名称查询：{}", e.getMessage());
        }
        return kw;
    }

    /* ================= 白名单映射 ================= */

    private String tableOf(String type) {
        if ("enterprise".equals(type)) return "enterprise_price";
        if ("international".equals(type)) return "international_price";
        return "market_price";
    }

    private String typeLabel(String type) {
        if ("enterprise".equals(type)) return "企业价格";
        if ("international".equals(type)) return "国际价格";
        return "市场价格";
    }

    /** 权限子句：非管理员追加「本人未过期授权品种」限定，userId 参数永远追加在 args 末尾 */
    private String permClause(boolean isAdmin) {
        return isAdmin ? "" :
                " AND varieties_id IN (SELECT varieties_id FROM data_permission" +
                        " WHERE user_id=? AND (expire_date IS NULL OR expire_date>=CURDATE()))";
    }

    private static final List<String> QUOTE_COLS = List.of(
            "varieties_id", "varieties_name", "market_name", "specifications_name",
            "unit_valuation_name", "data_date", "middle_price", "low_price", "high_price",
            "data_rise_or_fall", "data_rate");

    private static String colList() {
        return String.join(",", QUOTE_COLS);
    }

    /* ================= 商品名对齐 ================= */

    /** 按关键词搜商品（模糊包含，精确匹配优先）。只返回 id/name/unit/category */
    public List<Map<String, Object>> searchProducts(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return List.of();
        String sql = "SELECT varieties_id, name, unit, category FROM commodity" +
                " WHERE status=1 AND name LIKE ?" +
                " ORDER BY (name = ?) DESC, name LIMIT 30";
        return jdbcTemplate.queryForList(sql, "%" + kw + "%", kw);
    }

    /* ================= 工具 1：最新报价 ================= */

    /**
     * marketName 为空时返回该商品最新交易日的全部报价点（上限 25）；
     * 指定 marketName 时返回该报价点最新一条。
     */
    public Map<String, Object> latestQuote(int varietiesId, String type,
                                           String marketName, boolean isAdmin, Long userId) {
        String tbl = tableOf(type);
        marketName = resolveMarketName(tbl, varietiesId, marketName);
        List<Object> args = new ArrayList<>();
        args.add(varietiesId);

        String sql;
        if (marketName != null && !marketName.isBlank()) {
            args.add(marketName);
            sql = "SELECT " + colList() + " FROM " + tbl +
                    " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL" +
                    permClause(isAdmin) +
                    " ORDER BY data_date DESC LIMIT 1";
        } else {
            args.add(varietiesId);
            sql = "SELECT " + colList() + " FROM " + tbl +
                    " WHERE varieties_id=? AND data_date=(SELECT data_date FROM " + tbl +
                    " WHERE varieties_id=? AND middle_price IS NOT NULL ORDER BY data_date DESC LIMIT 1)" +
                    " AND middle_price IS NOT NULL" +
                    permClause(isAdmin) +
                    " ORDER BY market_name, specifications_name LIMIT 25";
        }
        if (!isAdmin) args.add(userId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tableLabel", typeLabel(type));
        out.put("rows", buildQuoteRows(rows, 0));
        if (!rows.isEmpty()) {
            Map<String, Object> first = rows.get(0);
            out.put("market", first.get("market_name"));
        }
        return out;
    }

    /**
     * 该品种在某价格类型下实际收录的报价点（按数据行数取前 N 个）。
     * 用于：用户指定了地区却查不到数据时，给出可替代的报价点，避免被误读成"整个品种没数据"。
     */
    public List<String> marketOptions(int varietiesId, String type, boolean isAdmin, Long userId, int limit) {
        String tbl = tableOf(type);
        // 只统计近 180 天：扫全品种历史要 8s+，近半年 0.5s，且给出的常用报价点基本一致
        String sql = "SELECT market_name, COUNT(*) n FROM " + tbl +
                " WHERE varieties_id=? AND middle_price >= 0" +
                " AND data_date >= DATE_SUB(CURDATE(), INTERVAL 180 DAY)" + permClause(isAdmin) +
                " GROUP BY market_name ORDER BY n DESC LIMIT " + Math.max(1, Math.min(20, limit));
        List<Object> args = new ArrayList<>();
        args.add(varietiesId);
        if (!isAdmin) args.add(userId);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
            List<String> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                if (r.get("market_name") != null) out.add(String.valueOf(r.get("market_name")));
            }
            return out;
        } catch (Exception e) {
            log.warn("查询报价点清单失败: {}", e.getMessage());
            return List.of();
        }
    }

    /* ================= 工具 2：日度走势 ================= */

    public Map<String, Object> trend(int varietiesId, int days, String type,
                                     String marketName, boolean isAdmin, Long userId) {
        String tbl = tableOf(type);
        marketName = resolveMarketName(tbl, varietiesId, marketName);
        // 未指定报价点 → 用【全市场均价】代表该商品整体走势。
        // 原逻辑会挑"数据行数最多的报价点"（PP 落到宁波），单个地区的价格不能代表全局。
        boolean allMarkets = (marketName == null || marketName.isBlank());
        String market = allMarkets ? "全市场均价" : marketName;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("allMarkets", allMarkets);
        out.put("tableLabel", typeLabel(type));

        int d = Math.max(1, Math.min(400, days));
        String sql;
        List<Object> args = new ArrayList<>();
        if (allMarkets) {
            // 全市场：每日对全部报价点取算术平均
            sql = "SELECT t.d AS d, t.m AS m FROM (SELECT data_date AS d, ROUND(AVG(middle_price), 0) AS m FROM " + tbl +
                    " WHERE varieties_id=? AND middle_price IS NOT NULL" +
                    " AND data_date >= DATE_SUB(CURDATE(), INTERVAL " + d + " DAY)" +
                    permClause(isAdmin) +
                    " GROUP BY data_date ORDER BY data_date DESC LIMIT 400) t ORDER BY t.d ASC";
            args.add(varietiesId);
        } else {
            // 指定报价点：每日取当日最高报价（MAX），避免同市场多条 business 报价造成折线锯齿/伪波动
            sql = "SELECT t.d AS d, t.m AS m FROM (SELECT data_date AS d, MAX(middle_price) AS m FROM " + tbl +
                    " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL" +
                    " AND data_date >= DATE_SUB(CURDATE(), INTERVAL " + d + " DAY)" +
                    permClause(isAdmin) +
                    " GROUP BY data_date ORDER BY data_date DESC LIMIT 400) t ORDER BY t.d ASC";
            args.add(varietiesId);
            args.add(marketName);
        }
        if (!isAdmin) args.add(userId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        List<Map<String, Object>> series = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        Double prevPrice = null;
        for (Map<String, Object> r : rows) {
            Object date = r.get("d");
            Object price = r.get("m");
            if (date == null || price == null) continue;
            double pv = ((Number) price).doubleValue();
            Double chg = prevPrice == null ? null : Math.round((pv - prevPrice) * 100) / 100.0;
            prevPrice = pv;
            Map<String, Object> sp = new LinkedHashMap<>();
            sp.put("date", String.valueOf(date));
            sp.put("price", pv);
            sp.put("change", chg);
            series.add(sp);
            dates.add(String.valueOf(date).substring(5)); // MM-dd
            values.add(pv);
        }
        out.put("series", series);
        out.put("chart", Map.of("dates", dates, "values", values));

        // 单位单独取（聚合查询不再带 unit 列）；全市场模式不按 market_name 过滤
        List<Object> uArgs = new ArrayList<>();
        uArgs.add(varietiesId);
        String uSql = "SELECT unit_valuation_name FROM " + tbl +
                " WHERE varieties_id=? AND middle_price IS NOT NULL";
        if (!allMarkets) {
            uSql += " AND market_name=?";
            uArgs.add(marketName);
        }
        uSql += permClause(isAdmin) + " LIMIT 1";
        if (!isAdmin) uArgs.add(userId);
        List<Map<String, Object>> uRows = jdbcTemplate.queryForList(uSql, uArgs.toArray());
        out.put("unit", uRows.isEmpty() ? null : uRows.get(0).get("unit_valuation_name"));
        return out;
    }

    /**
     * 主报价点：该商品在此表内数据行数最多的市场（仅统计近 180 天）。
     * <p>⚠️ 不限定时间会扫该品种全部历史，实测 PP 上要 8.37s，限定近 180 天后 0.49s，
     * 且结果一致 —— 主报价点本就是看"当前"哪个市场报价最多。
     */
    private String resolveMainMarket(int varietiesId, String tbl, boolean isAdmin, Long userId) {
        String sql = "SELECT market_name FROM " + tbl +
                " WHERE varieties_id=? AND middle_price IS NOT NULL" +
                " AND data_date>=DATE_SUB(CURDATE(), INTERVAL 180 DAY)" +
                permClause(isAdmin) +
                " GROUP BY market_name ORDER BY COUNT(*) DESC, market_name LIMIT 1";
        List<Object> args = new ArrayList<>(List.of(varietiesId));
        if (!isAdmin) args.add(userId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("market_name"));
    }

    /* ================= 工具 3：综合概览（6月统计/12月月度/涨跌频率/多市场对比） ================= */

    public Map<String, Object> overview(int varietiesId, String type,
                                        String marketName, boolean isAdmin, Long userId) {
        String tbl = tableOf(type);
        marketName = resolveMarketName(tbl, varietiesId, marketName);
        String market = marketName;
        if (market == null || market.isBlank()) {
            market = resolveMainMarket(varietiesId, tbl, isAdmin, userId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (market == null) {
            out.put("empty", true);
            return out;
        }
        out.put("tableLabel", typeLabel(type));
        out.put("market", market);

        List<Object> permArgs = new ArrayList<>();
        if (!isAdmin) permArgs.add(userId);
        String perm = permClause(isAdmin);

        // 1. 最新一条
        List<Object> a1 = new ArrayList<>(List.of(varietiesId, market));
        if (!isAdmin) a1.add(userId);
        String sqlLatest = "SELECT " + colList() + " FROM " + tbl +
                " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL" +
                perm + " ORDER BY data_date DESC LIMIT 1";
        List<Map<String, Object>> latestRows = jdbcTemplate.queryForList(sqlLatest, a1.toArray());
        if (latestRows.isEmpty()) {
            out.put("empty", true);
            return out;
        }
        out.put("latest", buildQuoteRows(latestRows, 0).get(0));
        out.put("unit", latestRows.get(0).get("unit_valuation_name"));

        // 2. 近 6 月统计
        List<Object> a2 = new ArrayList<>(List.of(varietiesId, market));
        if (!isAdmin) a2.add(userId);
        String sqlStats = "SELECT ROUND(MIN(middle_price),0) minPrice, ROUND(AVG(middle_price),0) avgPrice," +
                " ROUND(MAX(middle_price),0) maxPrice, COUNT(*) totalDays," +
                " ROUND(STDDEV(middle_price),0) stdDev FROM " + tbl +
                " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL" +
                " AND data_date>=DATE_SUB(CURDATE(), INTERVAL 6 MONTH)" + perm;
        List<Map<String, Object>> st = jdbcTemplate.queryForList(sqlStats, a2.toArray());
        out.put("stats6m", st.isEmpty() ? Map.of() : st.get(0));

        // 3. 近 12 月月度（均价/最高/最低）
        List<Object> a3 = new ArrayList<>(List.of(varietiesId, market));
        if (!isAdmin) a3.add(userId);
        String sqlMonthly = "SELECT DATE_FORMAT(data_date,'%Y-%m') month," +
                " ROUND(AVG(middle_price),0) avgPrice, ROUND(MAX(middle_price),0) highPrice," +
                " ROUND(MIN(middle_price),0) lowPrice FROM " + tbl +
                " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL" +
                " AND data_date>=DATE_SUB(CURDATE(), INTERVAL 12 MONTH)" + perm +
                " GROUP BY month ORDER BY month ASC";
        out.put("monthly12", jdbcTemplate.queryForList(sqlMonthly, a3.toArray()));

        // 4. 近 6 月涨跌频率
        List<Object> a4 = new ArrayList<>(List.of(varietiesId, market));
        if (!isAdmin) a4.add(userId);
        String sqlRf = "SELECT CASE WHEN data_rise_or_fall>0 THEN 'up' WHEN data_rise_or_fall<0 THEN 'down'" +
                " ELSE 'flat' END dir, COUNT(*) cnt, ROUND(AVG(data_rise_or_fall),2) avgChg FROM " + tbl +
                " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL" +
                " AND data_date>=DATE_SUB(CURDATE(), INTERVAL 6 MONTH)" + perm +
                " GROUP BY dir";
        out.put("riseFall6m", jdbcTemplate.queryForList(sqlRf, a4.toArray()));

        // 5. 同商品其他报价点近 6 月均价对比
        List<Object> a5 = new ArrayList<>(List.of(varietiesId, market));
        if (!isAdmin) a5.add(userId);
        String sqlPeers = "SELECT market_name, ROUND(AVG(middle_price),0) avgPrice, COUNT(*) days FROM " + tbl +
                " WHERE varieties_id=? AND market_name<>? AND middle_price IS NOT NULL" +
                " AND data_date>=DATE_SUB(CURDATE(), INTERVAL 6 MONTH)" + perm +
                " GROUP BY market_name ORDER BY avgPrice DESC LIMIT 8";
        out.put("peers", jdbcTemplate.queryForList(sqlPeers, a5.toArray()));

        return out;
    }

    /* ================= 工具 4.5：趋势参考解读（量化信号，仅基于真实历史数据） ================= */

    /**
     * 趋势参考解读：取主报价点近 70 个交易日的真实价格序列，服务端计算
     * MA5/MA10/MA20、短期/中期动量、区间分位、连涨连跌天数、波动率，
     * 再按固定打分规则合成「偏多/偏空/震荡 + 信号强度」档位与依据清单。
     * 模型只转述服务端结论，所有数字均为库内真实数据经服务端计算，不预测未来点位。
     */
    public Map<String, Object> outlook(int varietiesId, String type, String marketName,
                                       boolean isAdmin, Long userId) {
        String tbl = tableOf(type);
        marketName = resolveMarketName(tbl, varietiesId, marketName);
        String market = marketName;
        if (market == null || market.isBlank()) {
            market = resolveMainMarket(varietiesId, tbl, isAdmin, userId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (market == null) {
            out.put("empty", true);
            return out;
        }
        out.put("tableLabel", typeLabel(type));
        out.put("market", market);

        // 1. 主报价点近 70 个交易日的「市场级日线」：每日取当日最高报价（MAX）。
        //    market_price 按 business_id 存多条报价条目，同一品种+市场同日可能多价格，
        //    必须按日聚合后再算指标，否则同日多条会被误判为连续涨跌（准确性保障）。
        List<Object> a1 = new ArrayList<>(List.of(varietiesId, market));
        if (!isAdmin) a1.add(userId);
        String sqlSeries = "SELECT data_date AS d, MAX(middle_price) AS p FROM " + tbl +
                " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL" +
                permClause(isAdmin) +
                " GROUP BY data_date ORDER BY data_date DESC LIMIT 70";
        List<Map<String, Object>> seriesRows = jdbcTemplate.queryForList(sqlSeries, a1.toArray());
        java.util.Collections.reverse(seriesRows); // 升序

        List<Double> px = new ArrayList<>();
        List<String> dt = new ArrayList<>();
        for (Map<String, Object> s : seriesRows) {
            Double v = numOrNull(s.get("p"));
            if (v != null) {
                px.add(v);
                dt.add(String.valueOf(s.get("d")));
            }
        }
        if (px.size() < 8) {
            out.put("empty", true);
            return out;
        }
        Double cur = px.get(px.size() - 1);

        // 2. 商品名/单位 + 最新价与涨跌（按市场级日线自算：当日最高 - 上一交易日最高，
        //    不使用行内 data_rise_or_fall——那是 business 粒度，同一市场多条目时语义不对）
        List<Object> aName = new ArrayList<>(List.of(varietiesId, market));
        if (!isAdmin) aName.add(userId);
        List<Map<String, Object>> metaRows = jdbcTemplate.queryForList(
                "SELECT varieties_name, unit_valuation_name FROM " + tbl +
                        " WHERE varieties_id=? AND market_name=? AND middle_price IS NOT NULL" +
                        permClause(isAdmin) + " LIMIT 1", aName.toArray());
        String vname = metaRows.isEmpty() ? "" : String.valueOf(metaRows.get(0).get("varieties_name"));
        String unit = metaRows.isEmpty() || metaRows.get(0).get("unit_valuation_name") == null
                ? "" : String.valueOf(metaRows.get(0).get("unit_valuation_name"));

        Double chg = null;
        Double rate = null;
        if (px.size() >= 2 && px.get(px.size() - 2) > 0) {
            double prev = px.get(px.size() - 2);
            chg = Math.round((cur - prev) * 100) / 100.0;
            rate = Math.round((cur - prev) / prev * 1000) / 10.0;
        }
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("name", vname);
        latest.put("market", market);
        latest.put("spec", "");
        latest.put("price", cur);
        latest.put("unit", unit);
        latest.put("date", dt.get(px.size() - 1));
        latest.put("change", chg);
        latest.put("rate", rate);
        out.put("latest", latest);
        out.put("unit", unit);

        // 3. 基础统计
        int n = px.size();
        double curD = px.get(n - 1);
        double ma5 = sma(px, n - 5, 5);
        double ma10 = sma(px, n - 10, 10);
        double ma20 = sma(px, n - 20, 20);
        double ret5 = retPct(px, n - 1, 5);    // 近 5 交易日涨跌幅
        double ret20 = retPct(px, n - 1, 20);  // 近 20 交易日涨跌幅
        double vol20 = volPct(px, 20);         // 近 20 日波动率（日收益 std，%）

        // 区间分位（用近 6 月区间，SQL 已在上方？这里用序列窗口替代——改用整段序列 min/max 更贴合）
        double lo = px.stream().mapToDouble(Double::doubleValue).min().orElse(curD);
        double hi = px.stream().mapToDouble(Double::doubleValue).max().orElse(curD);
        double pct = hi > lo ? Math.round((curD - lo) / (hi - lo) * 1000) / 10.0 : 50.0;

        // 连涨/连跌天数（从最新往回数，看 data_date 相邻但用 price 差近似）
        int streak = 1;
        boolean up = px.get(n - 1) >= px.get(n - 2);
        for (int i = n - 1; i > 0; i--) {
            boolean curUp = px.get(i) >= px.get(i - 1);
            if (curUp == up) streak++;
            else break;
        }

        // 4. 打分规则（每一条生成可解释依据 + 分值）
        List<String> reasons = new ArrayList<>();
        int score = 0;
        // 均线多头/空头排列
        if (ma10 > ma20) {
            score += 1;
            reasons.add("MA10(" + trimNum(ma10) + ") > MA20(" + trimNum(ma20) + ")，中期均线多头排列，偏多");
        } else {
            score -= 1;
            reasons.add("MA10(" + trimNum(ma10) + ") < MA20(" + trimNum(ma20) + ")，中期均线空头排列，偏空");
        }
        // 价格相对 MA5
        if (curD > ma5) {
            score += 1;
            reasons.add("现价(" + trimNum(curD) + ") 站上 MA5(" + trimNum(ma5) + ")，短线偏强");
        } else {
            score -= 1;
            reasons.add("现价(" + trimNum(curD) + ") 跌破 MA5(" + trimNum(ma5) + ")，短线偏弱");
        }
        // 20 日动量
        if (ret20 > 3) {
            score += 1;
            reasons.add("近20日累计涨 " + trimNum(ret20) + "%，中期动能向上");
        } else if (ret20 < -3) {
            score -= 1;
            reasons.add("近20日累计跌 " + trimNum(ret20) + "%，中期动能向下");
        } else {
            reasons.add("近20日涨跌 " + trimNum(ret20) + "%，中期动能中性");
        }
        // 区间分位（过高回调风险/过低超跌反弹）
        if (pct >= 85) {
            score -= 1;
            reasons.add("价格处于序列区间高位（约" + trimNum(pct) + "%分位），历史上该位置短期回调概率偏高，偏谨慎");
        } else if (pct <= 20) {
            score += 1;
            reasons.add("价格处于序列区间低位（约" + trimNum(pct) + "%分位），历史上该位置短期企稳概率偏高，偏多");
        } else {
            reasons.add("价格处于序列区间中部（约" + trimNum(pct) + "%分位），无显著高/低位特征");
        }
        // 短线过热/超卖（5 日涨幅过大给回调分）
        if (ret5 > 4) {
            score -= 1;
            reasons.add("近5日急涨 " + trimNum(ret5) + "%，短线过热，追高需谨慎");
        } else if (ret5 < -4) {
            score += 1;
            reasons.add("近5日急跌 " + trimNum(ret5) + "%，短线超卖，或有技术性修复");
        }

        // 5. 合成方向档位（区间 [-4,4] 内，越正越偏多）
        String bias;
        String strength;
        if (score >= 3) {
            bias = "偏多";
            strength = "较强";
        } else if (score == 2) {
            bias = "偏多";
            strength = "中性偏强";
        } else if (score == 1) {
            bias = "震荡略偏多";
            strength = "中性";
        } else if (score == 0) {
            bias = "震荡";
            strength = "中性";
        } else if (score == -1) {
            bias = "震荡略偏空";
            strength = "中性";
        } else if (score == -2) {
            bias = "偏空";
            strength = "中性偏强";
        } else {
            bias = "偏空";
            strength = "较强";
        }

        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("score", score);
        sig.put("bias", bias);
        sig.put("strength", strength);
        sig.put("window", "近" + n + "个交易日");
        sig.put("asof", dt.get(n - 1));
        out.put("signal", sig);

        Map<String, Object> ind = new LinkedHashMap<>();
        ind.put("ma5", Math.round(ma5 * 100) / 100.0);
        ind.put("ma10", Math.round(ma10 * 100) / 100.0);
        ind.put("ma20", Math.round(ma20 * 100) / 100.0);
        ind.put("ret5", ret5);
        ind.put("ret20", ret20);
        ind.put("vol20", Math.round(vol20 * 100) / 100.0);
        ind.put("rangePct", pct);
        ind.put("streakUp", up);
        ind.put("streakDays", streak);
        out.put("indicators", ind);
        out.put("view", String.join("；", reasons) + "。");
        return out;
    }

    /** 简单移动平均：对 px 从 start 位置往前取 win 个元素的均值 */
    private static double sma(List<Double> px, int start, int win) {
        int from = Math.max(0, start - win + 1);
        double s = 0;
        int c = 0;
        for (int i = from; i <= start && i < px.size(); i++) {
            s += px.get(i);
            c++;
        }
        return c == 0 ? 0 : s / c;
    }

    /** 距末尾 back 个交易日、窗口 win 的区间涨跌幅（%） */
    private static double retPct(List<Double> px, int back, int win) {
        int end = px.size() - 1 - back;
        int start = Math.max(0, end - win + 1);
        if (start >= end || px.get(start) <= 0) return 0;
        return Math.round((px.get(end) - px.get(start)) / px.get(start) * 1000) / 10.0;
    }

    /** 近 win 个交易日日收益率的标准差（%），衡量波动率 */
    private static double volPct(List<Double> px, int win) {
        int n = px.size();
        if (n < win + 1) win = n - 1;
        double mean = 0;
        for (int i = n - win; i < n; i++) {
            double r = (px.get(i) - px.get(i - 1)) / px.get(i - 1);
            mean += r;
        }
        mean /= win;
        double v = 0;
        for (int i = n - win; i < n; i++) {
            double r = (px.get(i) - px.get(i - 1)) / px.get(i - 1);
            v += (r - mean) * (r - mean);
        }
        return Math.sqrt(v / Math.max(1, win - 1)) * 100;
    }

    /** 数字去掉无意义小数位：83.0 -> "83"，5.1 -> "5.1" */
    private static String trimNum(double v) {
        long r = Math.round(v * 10);
        if (r % 10 == 0) return String.valueOf(r / 10);
        return String.valueOf(r / 10.0);
    }

    /* ================= 工具 4：最新交易日涨跌排行 ================= */

    public List<Map<String, Object>> movers(String type, String direction, int topN,
                                            boolean isAdmin, Long userId) {
        String tbl = tableOf(type);
        int n = Math.max(1, Math.min(20, topN));
        int fetch = Math.min(300, Math.max(n * 5, 50));

        String sort;
        String flag;
        String dir = direction == null ? "" : direction.trim();
        if ("down".equalsIgnoreCase(dir) || "fall".equalsIgnoreCase(dir)) {
            flag = " data_rise_or_fall < 0 ";
            // 排序口径与网页端一致：按【涨跌额】(元/吨)（2026-09-10 用户指定）
            sort = " ORDER BY data_rise_or_fall ASC, middle_price DESC ";
        } else if ("flat".equalsIgnoreCase(dir)) {
            flag = " (data_rise_or_fall=0 OR data_rise_or_fall IS NULL) ";
            sort = " ORDER BY middle_price DESC ";
        } else {
            flag = " data_rise_or_fall > 0 ";
            sort = " ORDER BY data_rise_or_fall DESC, middle_price DESC ";
        }

        String sql = "SELECT " + colList() + " FROM " + tbl +
                " WHERE data_date=(SELECT data_date FROM " + tbl + " WHERE middle_price IS NOT NULL ORDER BY data_date DESC LIMIT 1)" +
                " AND middle_price IS NOT NULL AND " + flag +
                permClause(isAdmin) +
                sort + " LIMIT " + fetch;
        List<Object> args = new ArrayList<>();
        if (!isAdmin) args.add(userId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        if (rows.size() > n) rows = rows.subList(0, n);
        return buildQuoteRows(rows, 0);
    }

    /* ================= 结果规整 ================= */

    /** 行转前端/LLM 统一报价结构，指定 skip=0 从首行开始 */
    private List<Map<String, Object>> buildQuoteRows(List<Map<String, Object>> rows, int skip) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = skip; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("name", r.get("varieties_name"));
            q.put("market", r.get("market_name"));
            q.put("spec", emptyIfNull(r.get("specifications_name")));
            q.put("price", numOrNull(r.get("middle_price")));
            q.put("unit", r.get("unit_valuation_name"));
            q.put("date", r.get("data_date"));
            q.put("change", numOrNull(r.get("data_rise_or_fall")));
            q.put("rate", numOrNull(r.get("data_rate")));
            list.add(q);
        }
        return list;
    }

    private static Object emptyIfNull(Object o) {
        return o == null ? "" : o;
    }

    private static Double numOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number num) return num.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
