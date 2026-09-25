package com.datamarket.service.wechat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公众号（订阅号）智能问答 —— **快路径版**。
 * <p>
 * 为什么不用网页那套 AI？被动回复必须 **5 秒内**返回，而网页 AI 走两次大模型要 10~40 秒，
 * 必然超时（用户会看到"该公众号暂时无法提供服务"）。未认证个人号又没有客服消息接口，
 * 无法"先回执、再异步推送"。所以这里走**规则意图 + 直接查库 + 模板回答**，全程 <1 秒。
 * <p>
 * 能力（覆盖网页 AI 的「数据类」提问）：
 *   最新报价 / 近N日走势 / 涨跌排行 / 趋势信号（服务端算，非大模型预测）
 * 不覆盖：需要大模型组织语言的综合分析（那类问题会引导用户去网页版）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WechatMpService {

    private final JdbcTemplate jdbcTemplate;

    private static final int MAX_REPLY = 900;      // 微信文本消息上限约 2048 字节，留足余量
    private static final String BRAND = "ChemPrice 化工价格平台";

    /** 品种名缓存（10 分钟），避免每次消息都全表拉一遍 */
    private volatile List<String> nameCache = List.of();
    private volatile long nameCacheAt = 0L;

    /** 每个 openid 的最近提问时间（简单限流：同一人 3 秒内只处理一次） */
    private final Map<String, Long> lastAsk = new ConcurrentHashMap<>();

    /* ================= 对外入口 ================= */

    /** 处理一条文本消息，返回要回复的纯文本（已保证 5 秒内可完成） */
    public String answer(String openid, String content) {
        String q = content == null ? "" : content.trim();
        if (q.isEmpty()) return help();

        long now = System.currentTimeMillis();
        Long prev = lastAsk.get(openid);
        if (prev != null && now - prev < 3000) {
            return "提问太快啦，请 3 秒后再试～";
        }
        lastAsk.put(openid, now);
        if (lastAsk.size() > 5000) lastAsk.clear();   // 防内存膨胀

        try {
            if (isHelp(q)) return help();
            if (isRank(q)) return rankAnswer(q);
            if (isOutlook(q)) {
                String name = extractProduct(q);
                if (name != null) return outlookAnswer(name);
            }
            if (isTrend(q)) {
                String name = extractProduct(q);
                if (name != null) return trendAnswer(name, wantDays(q));
            }
            Integer daysBack = dateWord(q);
            if (daysBack != null) {
                String name = extractProduct(q);
                if (name != null) return dateAnswer(name, daysBack, q);
            }
            if (isRegion(q)) {
                String name = extractProduct(q);
                if (name != null) return regionAnswer(name);
            }
            String name = extractProduct(q);
            if (name != null) return latestAnswer(name);
            return help();
        } catch (Exception e) {
            log.warn("公众号问答失败: {}", e.getMessage());
            return "抱歉，查询出错了，请稍后重试。\n（如需完整智能问答，可访问网页版）";
        }
    }

    /* ================= 意图识别 ================= */

    private boolean isHelp(String q) {
        return q.length() <= 6 && (q.contains("帮助") || q.contains("help") || q.equalsIgnoreCase("hi")
                || q.contains("你好") || q.contains("怎么用") || q.contains("能问什么"));
    }

    private boolean isRank(String q) {
        return q.contains("排行") || q.contains("榜单") || q.contains("涨得最多") || q.contains("跌得最多")
                || q.contains("涨幅榜") || q.contains("跌幅榜") || q.toLowerCase().contains("top")
                || q.contains("哪些品种涨") || q.contains("哪些品种跌") || q.contains("涨跌榜");
    }

    private boolean isTrend(String q) {
        return q.contains("走势") || q.contains("趋势") || q.contains("近期") || q.contains("最近")
                || q.contains("这几天的") || q.contains("近几天");
    }

    private boolean isOutlook(String q) {
        return q.contains("会涨") || q.contains("会跌") || q.contains("预测") || q.contains("还能涨")
                || q.contains("能不能买") || q.contains("该不该") || q.contains("后市") || q.contains("怎么看")
                || q.contains("还会") || q.contains("分析一下");
    }

    private int wantDays(String q) {
        Matcher m = Pattern.compile("(近|最近|过去)?\\s*(\\d{1,2})\\s*天").matcher(q);
        if (m.find()) {
            int d = Integer.parseInt(m.group(2));
            return Math.max(2, Math.min(30, d));
        }
        if (q.contains("月")) return 30;
        return 7;
    }

    /* ================= 品种名提取 ================= */

    private List<String> allNames() {
        long now = System.currentTimeMillis();
        if (now - nameCacheAt < 600_000 && !nameCache.isEmpty()) return nameCache;
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT name FROM commodity WHERE status=1 AND name IS NOT NULL ORDER BY CHAR_LENGTH(name) DESC",
                    String.class);
            nameCache = names;
            nameCacheAt = now;
        } catch (Exception e) {
            log.warn("品种名缓存失败: {}", e.getMessage());
        }
        return nameCache;
    }

    /** 在问句里找品种名：取「最长命中」避免 PP 抢了 PP粉料；ASCII 名忽略大小写 */
    private String extractProduct(String q) {
        String up = q.toUpperCase();
        for (String n : allNames()) {
            if (n == null || n.length() < 2) continue;
            boolean ascii = n.chars().allMatch(c -> c < 128);
            if (ascii) {
                // 纯 ASCII 名（PP/ABS/PA6…）要求词边界，避免误命中英文单词内部
                Pattern p = Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(n) + "(?![A-Z0-9])");
                if (p.matcher(up).find()) return n;
            } else if (q.contains(n)) {
                return n;
            }
        }
        return null;
    }

    private Map<String, Object> findProduct(String name) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT varieties_id, name, unit, category FROM commodity WHERE status=1 AND name=? LIMIT 1", name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* ================= 各意图的回答 ================= */

    private String help() {
        return "你好，我是 ChemPrice 化工价格助手 👋\n"
                + "直接发消息就能查，例如：\n"
                + "· 甲醇多少钱\n"
                + "· PP 昨日报价\n"
                + "· PP 各地区价格\n"
                + "· PP 近 7 天走势\n"
                + "· 尿素近期走势\n"
                + "· 今天涨得最多的品种\n"
                + "· 纯苯后市会涨吗\n\n"
                + "数据来源：" + BRAND + "\n"
                + "（更复杂的分析请在电脑端使用网页版 AI 助手）";
    }

    private Date latestDate() {
        return jdbcTemplate.queryForObject("SELECT MAX(data_date) FROM market_price", Date.class);
    }

    private String latestAnswer(String name) {
        Map<String, Object> p = findProduct(name);
        if (p == null) return notFound(name);
        Date d = latestDate();
        if (d == null) return "价格库暂无数据。";
        return quoteSummary(name, p, d, "最新报价");
    }

    /** 某品种某交易日报价汇总（最新/昨日/历史共用一套口径；title 形如「最新报价」「昨日报价」） */
    private String quoteSummary(String name, Map<String, Object> p, Date d, String title) {
        int vid = ((Number) p.get("varieties_id")).intValue();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT market_name, specifications_name, middle_price, low_price, high_price,"
                        + " data_rise_or_fall, data_rate FROM market_price"
                        + " WHERE varieties_id=? AND data_date=? AND middle_price IS NOT NULL"
                        + " ORDER BY middle_price DESC LIMIT 200", vid, d);
        if (rows.isEmpty()) return "「" + name + "」在 " + d + "（" + title + "）暂无报价。";

        double sum = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        double chgSum = 0;
        int n = 0, up = 0, down = 0;
        for (Map<String, Object> r : rows) {
            double v = num(r.get("middle_price"));
            if (v <= 0) continue;
            sum += v;
            n++;
            double l = num(r.get("low_price"));
            double h = num(r.get("high_price"));
            if (l > 0) lo = Math.min(lo, l);
            if (h > 0) hi = Math.max(hi, h);
            double c = num(r.get("data_rise_or_fall"));
            chgSum += c;
            if (c > 0) up++;
            else if (c < 0) down++;
        }
        if (n == 0) return "「" + name + "」在 " + d + "（" + title + "）暂无有效报价。";

        double avg = sum / n;
        double avgChg = chgSum / n;
        String unit = p.get("unit") == null ? "元/吨" : String.valueOf(p.get("unit"));
        String arrow = avgChg > 0 ? "↑" : avgChg < 0 ? "↓" : "—";
        double pct = avg > 0 ? avgChg / (avg - avgChg) * 100 : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(name).append(" · ").append(title).append("】").append(d).append("\n");
        sb.append("主流均价：").append(fmt(avg)).append(" ").append(unit).append("\n");
        if (hi > lo) sb.append("区间：").append(fmt(lo)).append(" ~ ").append(fmt(hi)).append("\n");
        sb.append("较上一交易日：").append(arrow).append(" ").append(sign(avgChg)).append(" ").append(unit)
                .append("（").append(sign(pct)).append("%）\n");
        sb.append("报价点：").append(n).append(" 个（涨 ").append(up).append(" / 跌 ").append(down).append("）\n");
        sb.append("——\n数据来源：").append(BRAND);
        return sb.toString();
    }

    private String trendAnswer(String name, int days) {
        Map<String, Object> p = findProduct(name);
        if (p == null) return notFound(name);
        int vid = ((Number) p.get("varieties_id")).intValue();
        Date end = latestDate();
        if (end == null) return "价格库暂无数据。";
        Date start = Date.valueOf(end.toLocalDate().minusDays(days - 1L));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT data_date, AVG(middle_price) AS p FROM market_price"
                        + " WHERE varieties_id=? AND middle_price IS NOT NULL"
                        + " AND data_date BETWEEN ? AND ? GROUP BY data_date ORDER BY data_date",
                vid, start, end);
        if (rows.isEmpty()) return "「" + name + "」近 " + days + " 天暂无报价。";

        double first = num(rows.get(0).get("p"));
        double last = num(rows.get(rows.size() - 1).get("p"));
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Map<String, Object> r : rows) {
            double v = num(r.get("p"));
            if (v > 0) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        String unit = p.get("unit") == null ? "元/吨" : String.valueOf(p.get("unit"));
        double chg = first > 0 ? (last - first) / first * 100 : 0;
        String dir = chg > 0.5 ? "上行" : chg < -0.5 ? "下行" : "震荡";

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(name).append(" · 近 ").append(days).append(" 日走势】\n");
        sb.append("起 ").append(fmt(first)).append(" → 最新 ").append(fmt(last)).append(" ").append(unit).append("\n");
        sb.append("区间累计：").append(sign(chg)).append("%（").append(dir).append("）\n");
        if (hi > lo) sb.append("期间区间：").append(fmt(lo)).append(" ~ ").append(fmt(hi)).append("\n");
        sb.append("交易日数：").append(rows.size()).append("\n");
        sb.append("——\n数据来源：").append(BRAND);
        return sb.toString();
    }

    private String outlookAnswer(String name) {
        Map<String, Object> p = findProduct(name);
        if (p == null) return notFound(name);
        int vid = ((Number) p.get("varieties_id")).intValue();
        Date end = latestDate();
        if (end == null) return "价格库暂无数据。";
        Date start = Date.valueOf(end.toLocalDate().minusDays(29));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT data_date, AVG(middle_price) AS p FROM market_price"
                        + " WHERE varieties_id=? AND middle_price IS NOT NULL"
                        + " AND data_date BETWEEN ? AND ? GROUP BY data_date ORDER BY data_date",
                vid, start, end);
        if (rows.size() < 5) return "「" + name + "」历史数据不足，暂无法给出信号参考。";

        List<Double> v = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            double x = num(r.get("p"));
            if (x > 0) v.add(x);
        }
        double ma5 = avgOfLast(v, 5), ma20 = avgOfLast(v, 20), last = v.get(v.size() - 1);
        double lo = v.stream().min(Comparator.naturalOrder()).orElse(0d);
        double hi = v.stream().max(Comparator.naturalOrder()).orElse(0d);
        double pos = hi > lo ? (last - lo) / (hi - lo) * 100 : 50;
        double mom = v.size() >= 10
                ? (avgOfLast(v, 5) - avgRange(v, 5, 10)) / avgRange(v, 5, 10) * 100 : 0;

        String trend;
        if (ma5 > ma20 * 1.005) trend = "短期偏多（MA5 在 MA20 之上）";
        else if (ma5 < ma20 * 0.995) trend = "短期偏空（MA5 在 MA20 之下）";
        else trend = "震荡（MA5 与 MA20 交织）";
        String posDesc = pos >= 80 ? "已逼近 30 天区间高位，高位回调风险上升"
                : pos <= 20 ? "处于 30 天区间低位，低位反弹概率相对偏高"
                : "处于 30 天区间中部";
        String unit = p.get("unit") == null ? "元/吨" : String.valueOf(p.get("unit"));

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(name).append(" · 趋势信号参考】").append(end).append("\n");
        sb.append("最新 ").append(fmt(last)).append(" ").append(unit)
                .append("｜MA5 ").append(fmt(ma5)).append("｜MA20 ").append(fmt(ma20)).append("\n");
        sb.append("倾向：").append(trend).append("\n");
        sb.append("区间位置：30 天分位 ").append(fmt(pos)).append("%（").append(posDesc).append("）\n");
        sb.append("5 日动量：").append(sign(mom)).append("%\n");
        sb.append("——\n⚠️ 以上为基于本站历史数据的量化信号参考（非确定性预测），仅供参考，不构成投资或采购建议。\n");
        sb.append("数据来源：").append(BRAND);
        return sb.toString();
    }

    private String rankAnswer(String q) {
        boolean down = q.contains("跌") || q.contains("跌幅");
        Date d = latestDate();
        if (d == null) return "价格库暂无数据。";

        // 排序与展示必须用同一个口径：都按「涨跌幅」data_rate，而不是涨跌额
        String rateExpr = "AVG(CAST(REPLACE(REPLACE(IFNULL(data_rate,''),'%',''),'+','') AS DECIMAL(12,4)))";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT varieties_name, AVG(middle_price) AS p, " + rateExpr + " AS r"
                        + " FROM market_price WHERE data_date=? AND middle_price IS NOT NULL"
                        + " GROUP BY varieties_id, varieties_name"
                        + " HAVING COUNT(*) >= 2 AND ABS(" + rateExpr + ") > 0"
                        + " ORDER BY r " + (down ? "ASC" : "DESC") + " LIMIT 5", d);
        if (rows.isEmpty()) {
            return "最新交易日（" + d + "）没有明显的涨跌品种。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(d).append(down ? " 跌幅前 5" : " 涨幅前 5").append("】\n");
        int i = 1;
        for (Map<String, Object> r : rows) {
            sb.append(i++).append(". ").append(r.get("varieties_name"))
                    .append("  ").append(fmt(num(r.get("p")))).append(" 元/吨  ")
                    .append(sign(num(r.get("r")))).append("%\n");
        }
        sb.append("——\n数据来源：").append(BRAND);
        return sb.toString();
    }

    /* ================= 日期 / 地区 意图（2026-09-11 新增） ================= */

    private boolean isRegion(String q) {
        return q.contains("各地区") || q.contains("各地") || q.contains("地区") || q.contains("区域")
                || q.contains("报价点") || q.contains("分市场") || q.contains("哪些地方")
                || q.contains("哪些市场") || q.contains("哪里报价");
    }

    /**
     * 识别「昨天/昨日/前天/大前天/9月10日/2026-09-10」→ 相对最新交易日的天数差。
     * 纯字符串判断兜底，命中具体日期才查库，普通提问零额外开销。
     */
    private Integer dateWord(String q) {
        if (q.contains("大前天")) return 3;
        if (q.contains("前天")) return 2;
        if (q.contains("昨天") || q.contains("昨日")) return 1;
        Matcher m = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").matcher(q);
        java.time.LocalDate target = null;
        if (m.find()) {
            target = safeDate(m.group(1), m.group(2), m.group(3));
        } else {
            Matcher m2 = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?").matcher(q);
            if (m2.find()) {
                Date end0 = latestDate();
                String year = end0 == null ? String.valueOf(java.time.LocalDate.now().getYear())
                        : String.valueOf(end0.toLocalDate().getYear());
                target = safeDate(year, m2.group(1), m2.group(2));
            }
        }
        if (target == null) return null;
        Date end = latestDate();
        if (end == null) return null;
        long diff = java.time.temporal.ChronoUnit.DAYS.between(target, end.toLocalDate());
        if (diff < 0 || diff > 366 * 3) return null;   // 未来日期/过于久远 → 不按日期意图处理
        return (int) diff;
    }

    private java.time.LocalDate safeDate(String y, String m, String d) {
        try {
            return java.time.LocalDate.of(Integer.parseInt(y), Integer.parseInt(m), Integer.parseInt(d));
        } catch (Exception e) {
            return null;
        }
    }

    /** 昨日/前天/指定日期的历史报价：目标日往前找该品种最近一个有报价的交易日 */
    private String dateAnswer(String name, int daysBack, String q) {
        Map<String, Object> p = findProduct(name);
        if (p == null) return notFound(name);
        int vid = ((Number) p.get("varieties_id")).intValue();
        Date end = latestDate();
        if (end == null) return "价格库暂无数据。";
        Date target = Date.valueOf(end.toLocalDate().minusDays(daysBack));
        Date actual = jdbcTemplate.queryForObject(
                "SELECT MAX(data_date) FROM market_price WHERE varieties_id=? AND middle_price IS NOT NULL AND data_date <= ?",
                Date.class, vid, target);
        if (actual == null) return "「" + name + "」在 " + target + "（含）之前暂无报价。";
        String title = daysBack == 1 ? "昨日报价" : daysBack == 2 ? "前日报价" : "历史报价";
        if (q != null) {   // 用户明确写了日期 → 标题跟随用户说法（如「9月9日报价」）
            Matcher md = Pattern.compile("\\d{1,2}月\\d{1,2}[日号]?").matcher(q);
            if (md.find()) title = md.group() + "报价";
        }
        return quoteSummary(name, p, actual, title);
    }

    /** 各报价点（地区）当日均价拆分，按均价降序，最多列 12 个 */
    private String regionAnswer(String name) {
        Map<String, Object> p = findProduct(name);
        if (p == null) return notFound(name);
        int vid = ((Number) p.get("varieties_id")).intValue();
        Date d = latestDate();
        if (d == null) return "价格库暂无数据。";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT market_name, middle_price, data_rise_or_fall FROM market_price"
                        + " WHERE varieties_id=? AND data_date=? AND middle_price IS NOT NULL", vid, d);
        if (rows.isEmpty()) return "「" + name + "」在最新交易日（" + d + "）暂无报价。";

        // market_name 聚合：[价格和, 涨跌和, 条数]
        Map<String, double[]> agg = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            double v = num(r.get("middle_price"));
            if (v <= 0) continue;
            Object mnObj = r.get("market_name");
            String m = (mnObj == null || String.valueOf(mnObj).isBlank()) ? "未标注" : String.valueOf(mnObj);
            double[] a = agg.computeIfAbsent(m, k -> new double[3]);
            a[0] += v;
            a[1] += num(r.get("data_rise_or_fall"));
            a[2] += 1;
        }
        if (agg.isEmpty()) return "「" + name + "」在 " + d + " 暂无有效报价。";
        List<Map.Entry<String, double[]>> list = new ArrayList<>(agg.entrySet());
        list.sort((x, y) -> Double.compare(
                y.getValue()[0] / y.getValue()[2], x.getValue()[0] / x.getValue()[2]));

        String unit = p.get("unit") == null ? "元/吨" : String.valueOf(p.get("unit"));
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(name).append(" · 各报价点均价】").append(d).append("\n");
        int shown = 0;
        for (Map.Entry<String, double[]> e : list) {
            if (shown >= 12) break;
            double[] a = e.getValue();
            double avg = a[0] / a[2];
            double chg = a[1] / a[2];
            sb.append(shown + 1).append(". ").append(e.getKey()).append("  ")
                    .append(fmt(avg)).append(" ").append(unit);
            if (Math.abs(chg) > 1e-9 && avg - chg != 0) {
                sb.append("（").append(sign(chg / (avg - chg) * 100)).append("%）");
            }
            sb.append("\n");
            shown++;
        }
        if (list.size() > shown) {
            sb.append("…共 ").append(list.size()).append(" 个报价点，其余请上网页版查看\n");
        }
        sb.append("——\n数据来源：").append(BRAND);
        return sb.toString();
    }

    /* ================= 小工具 ================= */

    private String notFound(String name) {
        return "没找到「" + name + "」这个品种，换个说法试试？\n发「帮助」看我都能查什么。";
    }

    private static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).replace("%", "").replace("+", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fmt(double v) {
        if (Math.abs(v) >= 1000) return String.format("%,.0f", v);
        return String.format("%.2f", v);
    }

    private static String sign(double v) {
        return (v > 0 ? "+" : "") + fmt(v);
    }

    private static double avgOfLast(List<Double> v, int n) {
        int from = Math.max(0, v.size() - n);
        double s = 0;
        for (int i = from; i < v.size(); i++) s += v.get(i);
        return s / (v.size() - from);
    }

    /** 取 [size-to, size-from) 这段的均值，用于算动量基准 */
    private static double avgRange(List<Double> v, int from, int to) {
        int a = Math.max(0, v.size() - to);
        int b = Math.max(a, v.size() - from);
        if (b <= a) return v.get(v.size() - 1);
        double s = 0;
        for (int i = a; i < b; i++) s += v.get(i);
        return s / (b - a);
    }

    /** 供外部（管理端）观测用 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("品种名缓存", nameCache.size());
        m.put("活跃会话", lastAsk.size());
        return m;
    }
}
