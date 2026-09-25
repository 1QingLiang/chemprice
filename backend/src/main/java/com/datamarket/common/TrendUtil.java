package com.datamarket.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放 API 看板的趋势拼装工具。
 * 作用：把 SQL 返回的「有数据的日子」补成连续的 N 天序列（缺失日补 0），
 * 让前端图表不会因为跳日而画歪。
 */
public final class TrendUtil {

    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd");

    private TrendUtil() {
    }

    /**
     * @param raw  每日聚合结果（需含 date='yyyy-MM-dd'、calls、credits）
     * @param days 需要输出的天数（含今天）
     */
    public static List<Map<String, Object>> buildTrend(List<Map<String, Object>> raw, int days) {
        Map<String, Map<String, Object>> byDate = new HashMap<>();
        if (raw != null) {
            for (Map<String, Object> r : raw) {
                Object d = r.get("date");
                if (d != null) {
                    byDate.put(String.valueOf(d), r);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(days);
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        for (int i = 0; i < days; i++) {
            LocalDate day = start.plusDays(i);
            String key = day.toString();
            Map<String, Object> hit = byDate.get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", key);
            row.put("label", day.format(MM_DD));
            row.put("calls", hit == null ? 0L : asLong(hit.get("calls")));
            row.put("credits", hit == null ? BigDecimal.ZERO : asDec(hit.get("credits")));
            out.add(row);
        }
        return out;
    }

    /** 从趋势序列里取「今天」那一行（没有则返回 0），省一次查询 */
    public static Map<String, Object> pickToday(List<Map<String, Object>> trend) {
        String today = LocalDate.now().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calls", 0L);
        out.put("credits", BigDecimal.ZERO);
        if (trend != null) {
            for (Map<String, Object> r : trend) {
                if (today.equals(String.valueOf(r.get("date")))) {
                    out.put("calls", r.get("calls"));
                    out.put("credits", r.get("credits"));
                    break;
                }
            }
        }
        return out;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal asDec(Object o) {
        if (o instanceof BigDecimal b) {
            return b;
        }
        return o instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO;
    }
}
