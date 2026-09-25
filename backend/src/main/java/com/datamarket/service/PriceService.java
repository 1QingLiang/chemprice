package com.datamarket.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datamarket.entity.SysUser;
import com.datamarket.mapper.PriceMapper;
import com.datamarket.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PriceService {

    private final PriceMapper priceMapper;
    private final SysUserMapper userMapper;

    /**
     * 获取统计数据（按授权过滤）
     */
    public Map<String, Object> getStats(String username, String role, List<Integer> permittedIds) {
        Long userId = getUserIdFromUsername(username);
        return priceMapper.getStats(role, userId, permittedIds);
    }

    /**
     * 获取最新数据日涨跌统计（按授权过滤）
     */
    public Map<String, Object> getAugStats(String username, String role, List<Integer> permittedIds) {
        Long userId = getUserIdFromUsername(username);
        return priceMapper.getAugStats(role, userId, permittedIds);
    }

    /**
     * 获取价格走势
     */
    public List<Map<String, Object>> getTrend(Integer varietiesId, String startDate, String endDate, String tableType) {
        return priceMapper.getTrend(varietiesId, startDate, endDate, tableType);
    }

    /**
     * 获取涨跌排行（type: up/down/flat，默认 up）
     */
    public List<Map<String, Object>> getMoversTop(String type, Integer limit, String tableType) {
        String t = (type == null || type.isEmpty()) ? "up" : type;
        return priceMapper.getMoversTop(t, limit, tableType);
    }

    /**
     * 分页查询价格数据（支持全部类型，支持多品种）
     */
    public Map<String, Object> getMarketPrices(List<Integer> varietiesIds, String market,
                                               String marketName,
                                               String startDate, String endDate,
                                               int page, int size) {
        Map<String, Object> result = new HashMap<>();

        if (market == null || market.isBlank()) {
            // "全部类型"：三表 UNION ALL 后由 SQL 分页。
            // 原实现为内存分页（每表上限 1000 条），三表合计仅 3000 条可翻，
            // 第 301 页起必然空数据（真实总数约 2.2 万）。此处已改为 SQL 层分页。
            int offset = (page - 1) * size;
            List<Map<String, Object>> data = priceMapper.getMarketPricesUnion(
                    varietiesIds, marketName, startDate, endDate, offset, size);
            long totalCount = priceMapper.getMarketPricesUnionCount(
                    varietiesIds, marketName, startDate, endDate);
            result.put("data", data != null ? data : List.of());
            result.put("total", totalCount);
        } else {
            int offset = (page - 1) * size;
            List<Map<String, Object>> data = priceMapper.getMarketPrices(
                    varietiesIds, market, marketName, startDate, endDate, offset, size);
            long total = priceMapper.getMarketPricesCount(varietiesIds, market, marketName, startDate, endDate);
            result.put("data", data);
            result.put("total", total);
        }
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * 获取可用报价点列表（按价格类型 + 商品 + 日期范围联动过滤）。
     * market 为空时聚合三张表（市场/企业/国际）的去重报价点，供「全部类型」使用。
     */
    public List<String> getMarketNames(String market, List<Integer> varietiesIds,
                                       String startDate, String endDate) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (market == null || market.isBlank()) {
            for (String table : List.of("market", "enterprise", "international")) {
                List<String> names = priceMapper.getMarketNames(table, varietiesIds, startDate, endDate);
                if (names != null) set.addAll(names);
            }
        } else {
            List<String> names = priceMapper.getMarketNames(market, varietiesIds, startDate, endDate);
            if (names != null) set.addAll(names);
        }
        List<String> out = new ArrayList<>(set);
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * 获取价格矩阵
     */
    public Map<String, Object> getPriceMatrix(Integer varietiesId, String businessType,
                                               String startDate, String endDate) {
        List<String> dateList = new ArrayList<>();
        java.time.LocalDate start = java.time.LocalDate.parse(startDate);
        java.time.LocalDate end = java.time.LocalDate.parse(endDate);
        while (!start.isAfter(end)) {
            dateList.add(start.toString());
            start = start.plusDays(1);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("dates", dateList);
        result.put("data", priceMapper.getPriceMatrix(varietiesId, businessType, startDate, endDate));
        return result;
    }

    /**
     * 根据用户名获取用户ID
     */
    private Long getUserIdFromUsername(String username) {
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", username));
        return user != null ? user.getId() : null;
    }
}