package com.datamarket.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/**
 * 开放 API（/api/open/**）专用查询。
 * 只暴露公开行情数据，绝不包含用户/供应商等隐私表（adminOverview 除外 —— 那是管理员授权视图）。
 * 性能约定（2026-09-11 铁律）：
 * - latestDate 用 WHERE middle_price >= 0 + ORDER BY data_date DESC LIMIT 1（走 idx_date_mid；
 *   写 IS NOT NULL 会退化成全表扫描，626 万行约 7.9s）
 * - 日期统一 DATE_FORMAT 成 'yyyy-MM-dd' 字符串，避免 Jackson 对 java.sql.Date 的序列化差异
 *
 * 额度模型（2026-09-21 改为积分制）：
 * - 试用用户：注册即送 200 积分，自「首次建 Key」起 7 天有效
 * - 计费：/commodities 每次 0.05 积分，其余接口每次 0.1 积分（/meta 免费）
 * - 已授权用户 / 管理员：不受试用限制，走 daily_limit 日限额
 */
public interface OpenApiMapper {

    /** 全库最新交易日（走 idx_date_mid） */
    @Select("SELECT DATE_FORMAT(data_date, '%Y-%m-%d') FROM market_price " +
            "WHERE middle_price >= 0 ORDER BY data_date DESC LIMIT 1")
    String latestDate();

    /** 品种搜索（只返回上架品种） */
    @Select("SELECT varieties_id AS varietiesId, name, category, unit, " +
            "market_count AS marketCount, DATE_FORMAT(latest_date, '%Y-%m-%d') AS latestDate " +
            "FROM commodity WHERE status = 1 AND name LIKE CONCAT('%', #{q}, '%') " +
            "ORDER BY varieties_id LIMIT #{limit}")
    List<Map<String, Object>> searchCommodities(@Param("q") String q, @Param("limit") int limit);

    /** 按 ID 查单个品种 */
    @Select("SELECT varieties_id AS varietiesId, name, category, unit, " +
            "market_count AS marketCount, DATE_FORMAT(latest_date, '%Y-%m-%d') AS latestDate " +
            "FROM commodity WHERE status = 1 AND varieties_id = #{vid} LIMIT 1")
    Map<String, Object> findCommodity(@Param("vid") int vid);

    /** 某品种可用报价点列表（去重）：market / businessType / priceType / region / latestDate */
    @Select("<script>SELECT market_name AS market, business_type_name AS businessType, " +
            "price_type_name AS priceType, region_name AS region, " +
            "DATE_FORMAT(MAX(data_date), '%Y-%m-%d') AS latestDate " +
            "FROM market_price " +
            "WHERE varieties_id = #{vid} AND middle_price IS NOT NULL " +
            "<if test='bt != null and bt != &quot;&quot;'> AND business_type_name = #{bt} </if>" +
            "GROUP BY market_name, business_type_name, price_type_name, region_name " +
            "ORDER BY market_name LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> marketsByVid(@Param("vid") int vid, @Param("bt") String bt,
                                           @Param("limit") int limit);

    /** 某品种指定交易日的行情（date 必传；区间查询已于 2026-09-21 取消） */
    @Select("<script>SELECT market_name AS market, specifications_name AS specification, " +
            "price_type_name AS priceType, unit_valuation_name AS unit, region_name AS region, " +
            "business_type_name AS businessType, " +
            "DATE_FORMAT(data_date, '%Y-%m-%d') AS date, " +
            "low_price AS low, high_price AS high, middle_price AS mid, data_rise_or_fall AS changeAmt " +
            "FROM market_price " +
            "WHERE varieties_id = #{vid} AND data_date = #{date} AND middle_price IS NOT NULL " +
            "<if test='bt != null and bt != &quot;&quot;'> AND business_type_name = #{bt} </if>" +
            "<if test='pt != null and pt != &quot;&quot;'> AND price_type_name = #{pt} </if>" +
            "<if test='mkt != null and mkt != &quot;&quot;'> AND market_name = #{mkt} </if>" +
            "ORDER BY market_name, id LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> quotesOn(@Param("vid") int vid, @Param("date") String date,
                                       @Param("bt") String bt, @Param("pt") String pt,
                                       @Param("mkt") String mkt,
                                       @Param("limit") int limit);

    /** 某品种指定交易日的行情总条数（同 WHERE 条件，不含 LIMIT），用于 total 字段 */
    @Select("<script>SELECT COUNT(*) FROM market_price " +
            "WHERE varieties_id = #{vid} AND data_date = #{date} AND middle_price IS NOT NULL " +
            "<if test='bt != null and bt != &quot;&quot;'> AND business_type_name = #{bt} </if>" +
            "<if test='pt != null and pt != &quot;&quot;'> AND price_type_name = #{pt} </if>" +
            "<if test='mkt != null and mkt != &quot;&quot;'> AND market_name = #{mkt} </if>" +
            "</script>")
    long countQuotes(@Param("vid") int vid, @Param("date") String date,
                     @Param("bt") String bt, @Param("pt") String pt,
                     @Param("mkt") String mkt);

    /** 某品种可用维度（去重）：businessType + priceType + specification + market，供 /commodities/detail 使用 */
    @Select("SELECT DISTINCT business_type_name AS businessType, " +
            "price_type_name AS priceType, specifications_name AS specification, " +
            "market_name AS market, region_name AS region " +
            "FROM market_price WHERE varieties_id = #{vid} AND middle_price IS NOT NULL " +
            "ORDER BY business_type_name, price_type_name, market_name LIMIT #{limit}")
    List<Map<String, Object>> detailDimensions(@Param("vid") int vid, @Param("limit") int limit);

    /* ============ API Key 校验与用量 ============ */

    /**
     * 按 key 哈希查 key，并带出归属账号的档位与试用信息。
     * LEFT JOIN sys_user：让「禁用用户」的 key 自动失效（注解 SQL 不受 MP 逻辑删除影响）。
     * trialExpired：NULL=无试用记录 / 1=已过 7 天 / 0=试用中（日期运算全部下推到 SQL）。
     */
    @Select("SELECT k.id, k.name, k.key_hash AS keyHash, k.status, k.user_id AS userId, " +
            "u.username AS username, " +
            "k.daily_limit AS dailyLimit, k.calls_today AS callsToday, " +
            "DATE_FORMAT(k.call_date, '%Y-%m-%d') AS callDate, " +
            "u.status AS userStatus, u.role AS userRole, " +
            "u.open_api_enabled AS userEnabled, u.open_api_credits AS userCredits, " +
            "DATE_FORMAT(u.open_api_trial_start, '%Y-%m-%d %H:%i:%s') AS trialStart, " +
            "DATE_FORMAT(DATE_ADD(u.open_api_trial_start, INTERVAL 7 DAY), '%Y-%m-%d') AS trialEnd, " +
            "CASE WHEN u.open_api_trial_start IS NULL THEN NULL " +
            "WHEN NOW() > DATE_ADD(u.open_api_trial_start, INTERVAL 7 DAY) THEN 1 ELSE 0 END AS trialExpired " +
            "FROM open_api_key k LEFT JOIN sys_user u ON u.id = k.user_id " +
            "WHERE k.key_hash = #{hash} LIMIT 1")
    Map<String, Object> findKeyByHash(@Param("hash") String hash);

    /** 原子计数：跨天自动归零（当天第一次调用后 calls_today=1） */
    @Update("UPDATE open_api_key SET calls_today = IF(call_date = CURDATE(), calls_today + 1, 1), " +
            "call_date = CURDATE(), total_calls = total_calls + 1, last_called_at = NOW() " +
            "WHERE id = #{id}")
    int bumpUsage(@Param("id") int id);

    /** 原子扣积分：余额不足则不更新（影响行数=0），天然防并发超扣 */
    @Update("UPDATE sys_user SET open_api_credits = open_api_credits - #{cost} " +
            "WHERE id = #{uid} AND open_api_credits >= #{cost}")
    int deductCredits(@Param("uid") int uid, @Param("cost") java.math.BigDecimal cost);

    /** 首次建 Key 时写入试用起点（只写一次，永不重置 —— 防止删 Key 重建续命） */
    @Update("UPDATE sys_user SET open_api_trial_start = NOW() " +
            "WHERE id = #{uid} AND open_api_trial_start IS NULL")
    int startTrialOnce(@Param("uid") int uid);

    /**
     * 按天累加用量（upsert）。日增长看板与「总使用次数」的数据源。
     * uid=0 表示管理员手工创建的无归属 Key。
     */
    @Insert("INSERT INTO open_api_daily(stat_date, user_id, calls, credits) " +
            "VALUES(CURDATE(), #{uid}, 1, #{credits}) " +
            "ON DUPLICATE KEY UPDATE calls = calls + 1, credits = credits + #{credits}")
    int bumpDaily(@Param("uid") int uid, @Param("credits") java.math.BigDecimal credits);

    /* ============ 看板统计 ============ */

    /** 近 N 天的每日调用与积分（用户端：限本人） */
    @Select("SELECT DATE_FORMAT(stat_date, '%m-%d') AS label, " +
            "DATE_FORMAT(stat_date, '%Y-%m-%d') AS date, " +
            "SUM(calls) AS calls, SUM(credits) AS credits " +
            "FROM open_api_daily WHERE user_id = #{uid} " +
            "AND stat_date > DATE_SUB(CURDATE(), INTERVAL #{days} DAY) " +
            "GROUP BY stat_date ORDER BY stat_date")
    List<Map<String, Object>> dailyByUser(@Param("uid") int uid, @Param("days") int days);

    /** 近 N 天的每日调用与积分（管理端：全站） */
    @Select("SELECT DATE_FORMAT(stat_date, '%m-%d') AS label, " +
            "DATE_FORMAT(stat_date, '%Y-%m-%d') AS date, " +
            "SUM(calls) AS calls, SUM(credits) AS credits " +
            "FROM open_api_daily " +
            "WHERE stat_date > DATE_SUB(CURDATE(), INTERVAL #{days} DAY) " +
            "GROUP BY stat_date ORDER BY stat_date")
    List<Map<String, Object>> dailyAll(@Param("days") int days);

    /** 用户累计用量（自日统计表建表起）：调用次数 + 消耗积分 */
    @Select("SELECT COALESCE(SUM(calls), 0) AS calls, COALESCE(SUM(credits), 0) AS credits " +
            "FROM open_api_daily WHERE user_id = #{uid}")
    Map<String, Object> totalsByUser(@Param("uid") int uid);

    /** 全站累计用量（自日统计表建表起） */
    @Select("SELECT COALESCE(SUM(calls), 0) AS calls, COALESCE(SUM(credits), 0) AS credits " +
            "FROM open_api_daily")
    Map<String, Object> totalsAll();

    /** 全站历史调用总次数（权威值：key 上的累计计数，不受日统计表建表时间限制） */
    @Select("SELECT COALESCE(SUM(total_calls), 0) FROM open_api_key")
    long grandTotalCalls();

    /** 当日是否已有记录（用于判断今日是否有新增） */
    @Select("SELECT COALESCE(SUM(calls), 0) FROM open_api_daily WHERE stat_date = CURDATE()")
    long todayCallsAll();

    /* ============ 站内用户自助管理（/api/open-api/**） ============ */

    /** 用户名下全部 key（支持多 key）。callsToday 按当天归零：非今日的计数一律返回 0 */
    @Select("SELECT id, name, key_prefix AS keyPrefix, status, daily_limit AS dailyLimit, " +
            "CASE WHEN call_date = CURDATE() THEN calls_today ELSE 0 END AS callsToday, " +
            "DATE_FORMAT(call_date, '%Y-%m-%d') AS callDate, " +
            "total_calls AS totalCalls, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS createdAt, " +
            "DATE_FORMAT(last_called_at, '%Y-%m-%d %H:%i') AS lastCalledAt " +
            "FROM open_api_key WHERE user_id = #{uid} ORDER BY id DESC")
    List<Map<String, Object>> findKeysByUser(@Param("uid") int uid);

    /** 用户名下 key 总数（用于上限校验） */
    @Select("SELECT COUNT(*) FROM open_api_key WHERE user_id = #{uid}")
    int countKeysByUser(@Param("uid") int uid);

    /** 按 id + user_id 查 key（归属校验，杜绝跨用户操作） */
    @Select("SELECT id, name, key_prefix AS keyPrefix, status " +
            "FROM open_api_key WHERE id = #{keyId} AND user_id = #{uid} LIMIT 1")
    Map<String, Object> findKeyByIdAndUser(@Param("keyId") int keyId, @Param("uid") int uid);

    @Insert("INSERT INTO open_api_key(name, key_hash, key_prefix, status, daily_limit, user_id) " +
            "VALUES(#{name}, #{hash}, #{prefix}, 1, #{limit}, #{uid})")
    int insertUserKey(@Param("name") String name, @Param("hash") String hash,
                      @Param("prefix") String prefix, @Param("limit") int limit, @Param("uid") int uid);

    /** 按 id + user_id 更新状态（归属校验内建） */
    @Update("UPDATE open_api_key SET status = #{status} WHERE id = #{keyId} AND user_id = #{uid}")
    int setKeyStatus(@Param("keyId") int keyId, @Param("uid") int uid, @Param("status") int status);

    /** 按 id + user_id 改名 */
    @Update("UPDATE open_api_key SET name = #{name} WHERE id = #{keyId} AND user_id = #{uid}")
    int renameKey(@Param("keyId") int keyId, @Param("uid") int uid, @Param("name") String name);

    /** 按 id + user_id 删除（归属校验内建） */
    @Delete("DELETE FROM open_api_key WHERE id = #{keyId} AND user_id = #{uid}")
    int deleteKeyByIdAndUser(@Param("keyId") int keyId, @Param("uid") int uid);

    /** 停用某用户全部 key（管理员关闭授权时调用） */
    @Update("UPDATE open_api_key SET status = 0 WHERE user_id = #{uid} AND status = 1")
    int disableKeysOfUser(@Param("uid") int uid);

    /** 管理员重新开通：恢复既有 key 并同步限额 */
    @Update("UPDATE open_api_key SET status = 1, daily_limit = #{limit} WHERE user_id = #{uid}")
    int enableKeysOfUser(@Param("uid") int uid, @Param("limit") int limit);

    /* ============ 管理员授权（/api/admin/open-api/**） ============ */

    @Update("UPDATE sys_user SET open_api_enabled = #{enabled}, open_api_daily_limit = #{limit} " +
            "WHERE id = #{uid}")
    int updateUserOpenApi(@Param("uid") int uid, @Param("enabled") int enabled, @Param("limit") int limit);

    /** 积分充值（增量，可为负做冲正） */
    @Update("UPDATE sys_user SET open_api_credits = open_api_credits + #{amount} WHERE id = #{uid}")
    int addCredits(@Param("uid") int uid, @Param("amount") java.math.BigDecimal amount);

    /** 积分置为绝对值（重置余额用） */
    @Update("UPDATE sys_user SET open_api_credits = #{v} WHERE id = #{uid}")
    int setCredits(@Param("uid") int uid, @Param("v") java.math.BigDecimal v);

    /** 重设试用起点（延长/缩短有效期用：把起点回拨，使到期日 = 今天 + days） */
    @Update("UPDATE sys_user SET open_api_trial_start = #{start} WHERE id = #{uid}")
    int setTrialStart(@Param("uid") int uid, @Param("start") String start);

    /** 账号的开放 API 档位信息（积分 / 试用期 / 授权），供站内 status 接口使用 */
    @Select("SELECT u.id AS userId, u.role, u.status AS userStatus, " +
            "u.open_api_enabled AS enabled, u.open_api_daily_limit AS dailyLimit, " +
            "u.open_api_credits AS credits, " +
            "DATE_FORMAT(u.open_api_trial_start, '%Y-%m-%d %H:%i') AS trialStart, " +
            "DATE_FORMAT(DATE_ADD(u.open_api_trial_start, INTERVAL 7 DAY), '%Y-%m-%d') AS trialEnd, " +
            "CASE WHEN u.open_api_trial_start IS NULL THEN NULL " +
            "WHEN NOW() > DATE_ADD(u.open_api_trial_start, INTERVAL 7 DAY) THEN 1 ELSE 0 END AS trialExpired, " +
            "DATEDIFF(DATE_ADD(u.open_api_trial_start, INTERVAL 7 DAY), CURDATE()) AS trialDaysLeft " +
            "FROM sys_user u WHERE u.id = #{uid} LIMIT 1")
    Map<String, Object> openApiProfile(@Param("uid") int uid);

    /**
     * 管理端总览：全部用户 + 授权状态 / 积分 / 试用期 / key 汇总 / 积分消耗。
     * 多 key 场景下用 GROUP BY 聚合，避免行数膨胀；积分消耗用相关子查询取（不参与聚合，防重复计数）。
     * callsToday 必须按当天归零（calls_today 是惰性归零字段）。
     */
    @Select("SELECT u.id AS userId, u.username, u.nickname, u.role, u.status AS userStatus, " +
            "u.open_api_enabled AS enabled, u.open_api_daily_limit AS dailyLimit, " +
            "u.open_api_credits AS credits, " +
            "DATE_FORMAT(DATE_ADD(u.open_api_trial_start, INTERVAL 7 DAY), '%Y-%m-%d') AS trialEnd, " +
            "CASE WHEN u.open_api_trial_start IS NULL THEN NULL " +
            "WHEN NOW() > DATE_ADD(u.open_api_trial_start, INTERVAL 7 DAY) THEN 1 ELSE 0 END AS trialExpired, " +
            "COUNT(k.id) AS keyCount, " +
            "COALESCE(SUM(CASE WHEN k.status = 1 THEN 1 ELSE 0 END), 0) AS activeKeyCount, " +
            "COALESCE(SUM(CASE WHEN k.call_date = CURDATE() THEN k.calls_today ELSE 0 END), 0) AS callsToday, " +
            "COALESCE(SUM(k.total_calls), 0) AS totalCalls, " +
            "(SELECT COALESCE(SUM(d.calls), 0) FROM open_api_daily d " +
            " WHERE d.user_id = u.id AND d.stat_date = CURDATE()) AS todayCalls, " +
            "(SELECT COALESCE(SUM(d.credits), 0) FROM open_api_daily d " +
            " WHERE d.user_id = u.id AND d.stat_date = CURDATE()) AS todayCredits, " +
            "(SELECT COALESCE(SUM(d.credits), 0) FROM open_api_daily d " +
            " WHERE d.user_id = u.id) AS totalCredits " +
            "FROM sys_user u LEFT JOIN open_api_key k ON k.user_id = u.id " +
            "GROUP BY u.id, u.username, u.nickname, u.role, u.status, u.open_api_enabled, " +
            "u.open_api_daily_limit, u.open_api_credits, u.open_api_trial_start " +
            "ORDER BY u.open_api_enabled DESC, u.id ASC")
    List<Map<String, Object>> adminOverview();
}
