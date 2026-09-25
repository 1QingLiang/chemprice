package com.datamarket.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PriceMapper {

    /**
     * 获取统计数据（按角色+授权过滤）
     */
    Map<String, Object> getStats(@Param("role") String role,
                                 @Param("userId") Long userId,
                                 @Param("permittedIds") java.util.List<Integer> permittedIds);

    /**
     * 获取最新数据日的涨跌统计（按角色+授权过滤）
     */
    Map<String, Object> getAugStats(@Param("role") String role,
                                    @Param("userId") Long userId,
                                    @Param("permittedIds") java.util.List<Integer> permittedIds);

    /**
     * 获取价格走势
     */
    List<Map<String, Object>> getTrend(@Param("varietiesId") Integer varietiesId,
                                       @Param("startDate") String startDate,
                                       @Param("endDate") String endDate,
                                       @Param("tableType") String tableType);

    /**
     * 获取涨跌排行 TOP（type: up/down/flat）
     */
    List<Map<String, Object>> getMoversTop(@Param("type") String type,
                                           @Param("limit") Integer limit,
                                           @Param("tableType") String tableType);

    /**
     * 分页查询价格数据（支持多品种 IN 查询）
     */
    List<Map<String, Object>> getMarketPrices(@Param("varietiesIds") java.util.List<Integer> varietiesIds,
                                              @Param("market") String market,
                                              @Param("marketName") String marketName,
                                              @Param("startDate") String startDate,
                                              @Param("endDate") String endDate,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    /**
     * 获取价格数据总数（支持多品种 IN 查询）
     */
    long getMarketPricesCount(@Param("varietiesIds") java.util.List<Integer> varietiesIds,
                              @Param("market") String market,
                              @Param("marketName") String marketName,
                              @Param("startDate") String startDate,
                              @Param("endDate") String endDate);

    /**
     * 获取可用报价点列表（按商品/价格类型/日期范围联动过滤）
     * varietiesIds 为 null 表示不过滤商品
     */
    List<String> getMarketNames(@Param("tableType") String tableType,
                                @Param("varietiesIds") java.util.List<Integer> varietiesIds,
                                @Param("startDate") String startDate,
                                @Param("endDate") String endDate);

    /**
     * 获取价格矩阵（指定商品近N天，按市场分组的每日价格）
     */
    List<Map<String, Object>> getPriceMatrix(@Param("varietiesId") Integer varietiesId,
                                              @Param("businessType") String businessType,
                                              @Param("startDate") String startDate,
                                              @Param("endDate") String endDate);

    /** 「全部类型」三表 UNION ALL 分页（SQL 层分页，替代原内存分页） */
    List<Map<String, Object>> getMarketPricesUnion(
            @Param("varietiesIds") List<Integer> varietiesIds,
            @Param("marketName") String marketName,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate,
            @Param("offset") int offset,
            @Param("size") int size);

    /** 与 getMarketPricesUnion 同口径的总数 */
    long getMarketPricesUnionCount(
            @Param("varietiesIds") List<Integer> varietiesIds,
            @Param("marketName") String marketName,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate);

}
