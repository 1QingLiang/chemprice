package com.datamarket.service.ai;

import com.datamarket.common.Result;
import com.datamarket.entity.SysUser;
import com.datamarket.security.PermissionHelper;
import com.datamarket.service.AuditService;
import com.datamarket.controller.ChemController;
import com.datamarket.service.DemandService;
import com.datamarket.service.ExportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * AI 智能问答编排服务。
 * <p>
 * 流程：用户问题 → [LLM#1 意图→JSON 工具参数] → 商品名对齐(服务端) → 权限校验 →
 * 受控查询(AiQueryService) → [LLM#2 数据→中文回答] → 返回 {reply, chart?, quotes?}。
 * <p>
 * 权限：全部查询在 AiQueryService 中以当前登录用户身份执行（data_permission 子句），
 * 无权限商品在进入查询前即被拦截，LLM 永不接触 SQL 与连接。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatService {

    private final AiMimoClient mimoClient;
    private final AiQueryService queryService;
    /** 物性查询：复用 ChemController 已实现的映射表 + PubChem 24h 缓存，避免重复造轮子 */
    private final ChemController chemController;
    private final PermissionHelper permissionHelper;
    private final AuditService auditService;
    private final ExportService exportService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final DemandService demandService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 客服邮箱（无导出权限时提示用户联系） */
    private static final String SUPPORT_EMAIL = "chemprice@163.com";

    /* 每用户每分钟最多 15 次 */
    private final RateLimiter rateLimiter = new RateLimiter(15, 60_000);

    /** 发布意图的粗判：没有数据权限的用户也能发布求购/供应（与行情查询权限无关） */
    private static final java.util.regex.Pattern PUBLISH_INTENT = java.util.regex.Pattern.compile(
            "求购|采购|想买|帮我买|要买|供应|出售|现货");

    /**
     * 用法 / 平台帮助类问法。与「能看哪些行情数据」无关，
     * 所以**没有数据权限的用户也必须能问**（否则连"怎么用"都问不到，体验很差）。
     * 注意：这里只是不提前拦截，真正的取数权限仍在 resolveProduct(...) 里校验。
     */
    /**
     * 物性 / 安全性类问法。物性资料与「能看哪些行情数据」无关，
     * 且所有用户（含未实名、未认证）都能在「物性查询」页看到 —— 所以没有数据权限也必须能问。
     */
    private static final java.util.regex.Pattern CHEM_INTENT = java.util.regex.Pattern.compile(
            "物性|物理性质|理化性质|熔点|沸点|凝固点|密度|相对密度|闪点|蒸气压|蒸汽压|溶解性|外观|性状"
          + "|危化品|危险品|危险性|危险货物|危险类别|UN编号|UN 编号|包装类别|GHS"
          + "|运输要求|禁配物|储存条件|储存要求|怎么运输|怎么储存|怎么保存|SDS|MSDS|安全数据表");

    private static final java.util.regex.Pattern HELP_INTENT = java.util.regex.Pattern.compile(
            "怎么用|咋用|用法|使用说明|教程|怎么问|怎么查|怎么搜|怎么发|怎么导出|怎么下载|怎么设置"
          + "|怎么联系|联系方式|客服|帮助|收费|费用|开通|权限申请|数据来源|更新频率|公众号"
          + "|你是谁|能做什么|能干什么|会什么|有什么功能|平台介绍");

    /* 全局并发闸门：同时最多 4 个 AI 推理，防止多人并发拖垮模型通道与后端线程 */
    private final Semaphore aiGate = new Semaphore(4, true);

    /* 槽位满时先排队等待的秒数：能等到就正常回答，等不到才提示用户稍后重试 */
    private static final int GATE_WAIT_SEC = 12;

    /* 排队失败时建议用户等待后重试的秒数 */
    private static final int RETRY_AFTER_SEC = 10;

    /* 走势查询单次最大天数：支持「近一年」=365（原先是 90，用户问一年只给到 3 个月） */
    private static final int TREND_MAX_DAYS = 365;

    private static final int MAX_MSG = 500;
    private static final int MIN_MSG = 2;

    /* ================= 对外入口 ================= */

    public Map<String, Object> chat(String message) {
        boolean acquired;
        long waitStart = System.currentTimeMillis();
        try {
            // 先排队等待，避免高峰期一上来就把用户挡回去（排队期间不占用模型调用）
            acquired = aiGate.tryAcquire(GATE_WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            log.warn("AI 并发闸门已满，排队 {}s 仍未获得槽位", GATE_WAIT_SEC);
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("kind", "error");
            busy.put("hint", "BUSY");
            busy.put("reply", "当前同时咨询的用户较多，AI 正在忙（已为你排队 "
                    + GATE_WAIT_SEC + " 秒仍未轮到）。"
                    + "请稍等约 " + RETRY_AFTER_SEC + " 秒后，再点一次「发送」重试即可，你的问题不会丢失。");
            busy.put("retryAfterSec", RETRY_AFTER_SEC);
            return busy;
        }
        long waited = System.currentTimeMillis() - waitStart;
        if (waited > 800) {
            log.info("AI 闸门排队 {}ms 后获得槽位", waited);
        }
        try {
            return chatInner(message);
        } finally {
            aiGate.release();
        }
    }

    private Map<String, Object> chatInner(String message) {
        long start = System.currentTimeMillis();
        String username = currentUsername();
        Long userId = permissionHelper.getCurrentUserId();
        String safeMsg = message == null ? "" : message.trim();

        if (userId == null || username == null || username.isBlank()) {
            return Map.of("kind", "error", "reply", "登录状态已失效，请重新登录后再提问。", "hint", "AUTH");
        }
        if (!mimoClient.isConfigured()) {
            return Map.of("kind", "error", "reply", "AI 问答服务尚未启用（管理员未配置模型密钥），请联系管理员。", "hint", "CONFIG");
        }
        if (safeMsg.length() < MIN_MSG || safeMsg.length() > MAX_MSG) {
            return Map.of("kind", "error", "reply", "请把问题控制在 " + MIN_MSG + "~" + MAX_MSG + " 字之间。", "hint", "LENGTH");
        }
        if (!rateLimiter.allow(userId)) {
            return Map.of("kind", "error", "reply", "提问太频繁啦，请一分钟后再试。", "hint", "LIMIT");
        }

        String tool = "none";
        int dataRows = 0;
        try {
            Map<String, Object> resp = doChat(userId, username, safeMsg);
            tool = String.valueOf(resp.getOrDefault("_tool", "none"));
            dataRows = resp.containsKey("_rows") ? ((Number) resp.get("_rows")).intValue() : 0;
            resp.remove("_tool");
            resp.remove("_rows");

            int cost = (int) (System.currentTimeMillis() - start);
            String after = jsonOf(Map.of(
                    "question", safeMsg, "tool", tool,
                    "rows", dataRows, "costMs", cost));
            auditService.recordAs("AI_CHAT", "BUSINESS", "AI", String.valueOf(userId),
                    null, after, "answer".equals(resp.get("kind")) ? 1 : 0,
                    "answer".equals(resp.get("kind")) ? "INFO" : "WARN",
                    "AI 问答[" + tool + "] 命中 " + dataRows + " 行", username);

            // 落库：管理员在「AI 问答记录」页可看到问题原文、AI 回答与 Token 消耗
            String aiReply = String.valueOf(resp.getOrDefault("reply", ""));
            if (aiReply.isBlank()) aiReply = String.valueOf(resp.getOrDefault("hint", ""));
            saveAiLog(userId, username, safeMsg, aiReply, tool,
                    String.valueOf(resp.get("kind")), mimoClient.takeLastUsage(), cost);
            return resp;
        } catch (Exception e) {
            log.error("AI 问答异常: {}", e.getMessage(), e);
            auditService.errorAs("AI_CHAT", "BUSINESS", "AI", String.valueOf(userId),
                    null, jsonOf(Map.of("question", safeMsg, "tool", tool)),
                    "AI 问答失败: " + e.getMessage(), username);
            saveAiLog(userId, username, safeMsg, "（异常中断：" + e.getMessage() + "）",
                    tool, "error", mimoClient.takeLastUsage(),
                    (int) (System.currentTimeMillis() - start));
            return Map.of("kind", "error",
                    "reply", e instanceof IllegalStateException ? e.getMessage() : "AI 服务开小差了，请稍后再试。",
                    "hint", "EXCEPTION");
        }
    }

    /* ================= 主流程 ================= */

    private Map<String, Object> doChat(Long userId, String username, String message) throws Exception {
        List<Integer> permitted = permissionHelper.getPermittedVarietiesIds();
        boolean isAdmin = permitted == null;
        // 发布供需、问「怎么用」都与「能看哪些行情数据」无关：
        // 没有数据权限的用户也应能发布求购/供应、也应能查到平台用法说明。
        boolean publishIntent = PUBLISH_INTENT.matcher(message).find();
        boolean helpIntent = HELP_INTENT.matcher(message).find();
        boolean chemIntent = CHEM_INTENT.matcher(message).find();
        if (!isAdmin && permitted.isEmpty() && !publishIntent && !helpIntent && !chemIntent) {
            return okResp(Map.of("kind", "error",
                    "reply", "你还没有被授权的数据品种，无法查询价格。请联系管理员开通数据权限后再来提问。",
                    "hint", "NO_PERM"), "none", 0);
        }

        // ========== LLM#1：意图 → JSON ==========
        JsonNode intent = null;
        for (int attempt = 0; attempt < 2 && intent == null; attempt++) {
            // 意图识别走「快速通道」：小模型 + 关思考。
            // 关思考是因为思考会吃满 max_tokens 导致 content 为空（历史上的 PARSE 失败根因）；
            // 用小模型是因为意图只是把问句映射成工具名，实测 2.7s → 1.0s 且判定一致。
            String raw = mimoClient.chatFast(INTENT_SYS,
                    attempt == 0 ? message : "必须严格只输出 JSON。问题：" + message, 700);
            intent = parseJsonObject(raw);
        }
        if (intent == null || !intent.has("tool")) {
            return okResp(Map.of("kind", "error",
                    "reply", "抱歉，我没能理解这个问题，换个问法试试，比如：丙烯今天多少钱 / 甲醇最近走势 / 今天哪些品种涨得最多。",
                    "hint", "PARSE"), "none", 0);
        }
        String tool = intent.path("tool").asText("").trim();
        String clarifyText = intent.hasNonNull("clarify") ? intent.path("clarify").asText() : null;
        JsonNode args = intent.path("args");

        // ★ 规则兜底：大模型偶尔会把「XX 最新价格」这类标准问法漏判成 none（实测「甲醇最新价格」）。
        // 只要句子里出现价格类问法、且能从中扒出一个数据库里确实存在的商品名，就按「查最新价」处理。
        if ("none".equals(tool) || tool.isEmpty()) {
            String guess = guessProduct(message);
            if (guess != null) {
                com.fasterxml.jackson.databind.node.ObjectNode o = objectMapper.createObjectNode();
                o.put("product", guess);
                args = o;
                tool = "latest_price";
                log.info("意图兜底：把「{}」改判为 latest_price（商品={}）", message, guess);
            }
        }

        if ("none".equals(tool) || tool.isEmpty()) {
            if (clarifyText != null && !clarifyText.isBlank()) {
                return okResp(Map.of("kind", "clarify", "reply", "", "hint", clarifyText), "none", 0);
            }
            return okResp(Map.of("kind", "clarify", "reply", "",
                    "hint", "这个问题超出 AI 查询能力（仅支持本站价格数据的最新价/走势/排行/综合概览查询）。"), "none", 0);
        }

        switch (tool) {
            case "latest_price", "latest", "price": {
                ProductRef p = resolveProduct(args, isAdmin, permitted, message);
                if (p.needClarify()) return p.toResp();
                String type = argText(args, "type", "market");
                String mkt = nullableArg(args, "market");
                Map<String, Object> r = queryService.latestQuote(p.id(), type,
                        mkt, isAdmin, userId);
                List<Map<String, Object>> rows = rowsOf(r);
                if (rows.isEmpty()) return noDataResp(p, type, mkt, isAdmin, userId);
                // 服务端把真实数据写成「事实清单」，模型只能转述、无法编造数字
                Map<String, Object> packMap = new LinkedHashMap<>();
                packMap.put("权威事实-只可转述不可改动", latestFacts(p, rows, r));
                String pack = jsonOf(packMap);
                // 若模型未产生文字，给一段由服务端数据生成的保底摘要
                String fallback = String.join("\n", latestFacts(p, rows, r));
                String reply = cleanAnswer(mimoClient.chat(ANSWER_SYS + "\n\n【查询数据】\n" + pack, message, 1200, true));
                if (reply == null || reply.isBlank()) reply = fallback;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("kind", "answer");
                data.put("reply", reply);
                data.put("quotes", rows);
                data.put("chart", null);
                return okResp(data, tool, rows.size());
            }
            case "price_trend", "trend", "price_trend_days": {
                ProductRef p = resolveProduct(args, isAdmin, permitted, message);
                if (p.needClarify()) return p.toResp();
                String type = argText(args, "type", "market");
                // 用户说「近一年」就按 365 查；超过上限时明确告知，不静默降级
                int requestedDays = clampInt(args, "days", 30, 1, 3650);
                int days = Math.min(requestedDays, TREND_MAX_DAYS);
                boolean dayCapped = requestedDays > TREND_MAX_DAYS;
                String marketArg = nullableArg(args, "market");
                Map<String, Object> r = queryService.trend(p.id(), days, type,
                        nullableArg(args, "market"), isAdmin, userId);
                if (Boolean.TRUE.equals(r.get("empty"))) return noDataResp(p, type, marketArg, isAdmin, userId);
                List<Map<String, Object>> series = seriesOf(r);
                if (series.isEmpty()) return noDataResp(p, type, marketArg, isAdmin, userId);

                Map<String, Object> chart = chartOf(r, p, type);
                Map<String, Object> packMap = new LinkedHashMap<>();
                packMap.put("tableLabel", r.get("tableLabel"));
                packMap.put("商品", p.name());
                packMap.put("市场", r.get("market"));
                packMap.put("单位", r.get("unit"));
                packMap.put("查询天数", days);
                packMap.put("实际数据点数", series.size());
                packMap.put("区间摘要", trendSummary(series));
                String pack = jsonOf(packMap);
                String reply = cleanAnswer(mimoClient.chat(ANSWER_SYS + "\n\n【查询数据】\n" + pack, message, 1200, true));
                if (reply == null || reply.isBlank()) {
                    reply = "「" + p.name() + "·" + r.get("market") + "」近 " + days + " 天共 "
                            + series.size() + " 个交易日数据已查得，走势见下方图表。";
                }
                if (dayCapped) {
                    reply = "你要求的是近 " + requestedDays + " 天，超出走势查询上限（最多 "
                            + TREND_MAX_DAYS + " 天），已按 " + days + " 天给出。\n\n" + reply;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("kind", "answer");
                data.put("reply", reply);
                data.put("chart", chart);
                data.put("quotes", null);
                return okResp(data, tool, series.size());
            }
            case "price_overview", "overview", "analysis": {
                ProductRef p = resolveProduct(args, isAdmin, permitted, message);
                if (p.needClarify()) return p.toResp();
                String type = argText(args, "type", "market");
                Map<String, Object> r = queryService.overview(p.id(), type,
                        nullableArg(args, "market"), isAdmin, userId);
                if (Boolean.TRUE.equals(r.get("empty"))) return p.noData(type);
                // 服务端把统计算成「事实清单」，模型只能组织语言、禁止再自算数字
                List<String> facts = overviewFacts(p, r);
                Map<String, Object> packMap = new LinkedHashMap<>();
                packMap.put("权威事实-只可转述不可改动", facts);
                packMap.put("用户问题", message);
                String pack = jsonOf(packMap);
                String reply = cleanAnswer(mimoClient.chat(ANSWER_SYS + "\n\n【查询数据】\n" + pack, message, 1500, true));
                if (reply == null || reply.isBlank()) reply = String.join("\n", facts);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("kind", "answer");
                data.put("reply", reply);
                data.put("chart", null);
                data.put("quotes", null);
                return okResp(data, tool, 1);
            }
            case "rank_movers", "movers", "rank": {
                String type = argText(args, "type", "market");
                String direction = argText(args, "direction", "up");
                int topN = clampInt(args, "top_n", 10, 1, 20);
                List<Map<String, Object>> rows = queryService.movers(type, direction, topN, isAdmin, userId);
                if (rows.isEmpty()) {
                    Map<String, Object> emptyResp = new LinkedHashMap<>();
                    emptyResp.put("kind", "answer");
                    emptyResp.put("reply", "最新交易日的涨跌排行暂无数据（可能当天没有符合该方向的价格变动）。");
                    emptyResp.put("quotes", null);
                    emptyResp.put("chart", null);
                    return okResp(emptyResp, tool, 0);
                }
                String dirLabel = "down".equalsIgnoreCase(direction) || "fall".equalsIgnoreCase(direction) ? "下跌"
                        : "flat".equalsIgnoreCase(direction) ? "持平" : "上涨";
                String pack = jsonOf(Map.of("方向", dirLabel, "最新交易日", rows.get(0).get("date"), "排行", rows));
                String reply = cleanAnswer(mimoClient.chat(ANSWER_SYS + "\n\n【查询数据】\n" + pack, message, 1200, true));
                if (reply == null || reply.isBlank()) {
                    reply = "最新交易日" + dirLabel + "排行共取到 " + rows.size() + " 个品种（详见下方列表），榜首为「"
                            + rows.get(0).get("name") + "」" + rows.get(0).get("price") + " " + rows.get(0).get("unit") + "。";
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("kind", "answer");
                data.put("reply", reply);
                data.put("quotes", rows);
                data.put("chart", null);
                return okResp(data, tool, rows.size());
            }
            case "trend_outlook", "outlook", "predict", "forecast", "future", "trend_analysis": {
                ProductRef p = resolveProduct(args, isAdmin, permitted, message);
                if (p.needClarify()) return p.toResp();
                String type = argText(args, "type", "market");
                Map<String, Object> r = queryService.outlook(p.id(), type,
                        nullableArg(args, "market"), isAdmin, userId);
                if (Boolean.TRUE.equals(r.get("empty"))) return p.noData(type);
                // 服务端算好的事实清单：只含历史统计，不含任何未来预测
                List<String> facts = outlookFacts(p, r);
                Map<String, Object> packMap = new LinkedHashMap<>();
                packMap.put("权威事实-只可转述不可改动", facts);
                packMap.put("用户问题", message);
                packMap.put("铁律", "你是基于历史数据的化工行情分析师：必须依据上述【权威事实】中的综合信号先给出明确的倾向性结论（如：短期信号偏多/偏空/震荡、动能偏强/偏弱、处于高位回调概率偏高/低位反弹概率偏高等），这是基于历史统计的概率性参考而非保证；不得编造任何数据，不得断言「一定涨/一定跌」，不得给出未来具体价位或某日精确涨跌的预测；切勿用「无法预测」这类话敷衍开头——先给倾向结论与依据，再提示仅供参考。");
                String pack = jsonOf(packMap);
                String reply = cleanAnswer(mimoClient.chat(ANSWER_SYS + "\n\n【查询数据】\n" + pack, message, 1500, true));
                // 模型吐字偶发截断/过短时，直接用服务端完整事实清单作答（保准确、不残缺）
                if (reply == null || reply.isBlank() || reply.trim().length() < 30) {
                    reply = String.join("\n", facts);
                }
                // 免责声明强制兜底（模型漏写则由服务端补上）
                String disclaimer = "\n\n⚠️ 以上为基于本站历史数据的量化信号参考（非确定性预测），仅供参考，不构成任何投资或采购建议。化工品价格波动频繁，实际请以每日最新报价为准。";
                if (!reply.contains("仅供参考") && !reply.contains("免责") && !reply.contains("不构成")) {
                    reply = reply + disclaimer;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("kind", "answer");
                data.put("reply", reply);
                data.put("chart", null);
                data.put("quotes", null);
                return okResp(data, tool, 1);
            }
            case "export_data", "export", "export_excel", "download", "excel": {
                // ① 产品权限：该品种必须在本用户的 data_permission 授权范围内
                String blocked = deniedProductName(args, isAdmin, permitted);
                if (blocked != null) {
                    return okResp(ansResp("你当前没有「" + blocked
                            + "」的产品权限，无法导出该产品的数据。如需开通，请联系管理员。"), tool, 0);
                }
                ProductRef p = resolveProduct(args, isAdmin, permitted, message);
                if (p.needClarify()) return p.toResp();

                // ② 导出权限：sys_user.export_permission（ADMIN 默认具备）
                SysUser me = permissionHelper.getCurrentUser();
                boolean canExport = me != null
                        && ("ADMIN".equals(me.getRole()) || Integer.valueOf(1).equals(me.getExportPermission()));
                if (!canExport) {
                    auditService.warn("EXPORT_AI_DENIED", "DATA", "EXPORT", String.valueOf(p.id()), null,
                            jsonOf(Map.of("product", p.name())), "导出被拒：无数据导出权限");
                    return okResp(ansResp("你当前没有数据导出权限。如需开通，请发送邮件联系客服：" + SUPPORT_EMAIL
                            + "\n（邮件中请注明你的账号与需要的产品）"), tool, 0);
                }

                // ③ 导出范围：服务端硬夹紧 1~7 天；超出上限时在回复里明确说明，不静默降级
                int requestedDays = clampInt(args, "days", 7, 1, 3650);
                int days = Math.min(requestedDays, ExportService.MAX_DAYS);
                boolean dayCapped = requestedDays > ExportService.MAX_DAYS;
                try {
                    ExportService.Result r = exportService.create(p.id(), p.name(), days);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("kind", "answer");
                    StringBuilder sb = new StringBuilder();
                    if (dayCapped) {
                        sb.append("你要求的是近 ").append(requestedDays).append(" 天，超出单次导出上限（最多 ")
                          .append(ExportService.MAX_DAYS).append(" 天），已按 ").append(r.days())
                          .append(" 天导出。\n如需更长区间，请缩小范围后分次导出，或发邮件联系客服：")
                          .append(SUPPORT_EMAIL).append("\n\n");
                    }
                    sb.append("已生成「").append(p.name()).append("」近 ").append(r.days()).append(" 天（")
                      .append(r.startDate()).append(" ~ ").append(r.endDate()).append("）的价格数据 Excel，共 ")
                      .append(r.rows()).append(" 行，包含市场价 / 企业价 / 国际价三个工作表。\n")
                      .append("点击下方按钮即可下载（下载链接 30 分钟内有效）。");
                    if (r.truncated()) {
                        sb.append("\n\n注：该区间数据量较大，每个工作表最多导出 6 万行；")
                          .append("如需完整数据，请缩小时间范围后分次导出。");
                    }
                    data.put("reply", sb.toString());
                    Map<String, Object> ex = new LinkedHashMap<>();
                    ex.put("url", "/api/ai/export?t=" + r.token());
                    ex.put("filename", r.fileName());
                    ex.put("rows", r.rows());
                    ex.put("days", r.days());
                    ex.put("start", r.startDate());
                    ex.put("end", r.endDate());
                    ex.put("product", p.name());
                    data.put("export", ex);
                    data.put("chart", null);
                    data.put("quotes", null);
                    return okResp(data, tool, r.rows());
                } catch (IllegalStateException he) {
                    String msg = "EMPTY".equals(he.getMessage())
                            ? "「" + p.name() + "」近 " + days + " 天没有可导出的价格数据。可换个品种，或先看看它的最新报价。"
                            : he.getMessage();
                    return okResp(ansResp(msg), tool, 0);
                }
            }
            case "publish_demand": {
                // AI 代发总开关（管理端「用户管理 → AI 功能」可关；site_setting: ai_publish_enabled，缺省=开）
                if (!aiPublishEnabled()) {
                    return okResp(Map.of("kind", "clarify", "reply", "",
                            "hint", "AI 代发供需功能暂未开放，请到「供需广场」手动发布。"), tool, 0);
                }
                String type = argText(args, "type", "demand");
                if (!"demand".equals(type) && !"supply".equals(type)) type = "demand";
                String product = argText(args, "product", "");
                // 新产品名在 items[] 里；只有当 product 和 items 都为空才算缺参数
                boolean hasItems = args != null && args.path("items").isArray() && args.path("items").size() > 0;
                if (product.isEmpty() && !hasItems) {
                    return okResp(Map.of("kind", "clarify", "reply", "",
                            "hint", "请告诉我要发布哪个产品的供需信息，例如「帮我求购PP 30吨」。"), tool, 0);
                }
                SysUser u = permissionHelper.getCurrentUser();
                if (u == null) {
                    return okResp(Map.of("kind", "clarify", "reply", "",
                            "hint", "请先登录后再发布供需信息。"), tool, 0);
                }
                String qty = argText(args, "quantity", "");
                String unit = argText(args, "unit", "吨");
                if (unit.isBlank()) unit = "吨";
                String price = argText(args, "expectPrice", "");
                String spec = argText(args, "spec", "");
                String region = nullableArg(args, "region");

                // 多产品 -> 多行明细（支持像「求购 PP 30吨、PE 50吨」一次发布一条多行帖）
                java.util.List<Map<String, Object>> items =
                        parseAiItems(args, product, qty, unit, price, spec);
                if (items == null || items.isEmpty()) {
                    return okResp(Map.of("kind", "clarify", "reply", "",
                            "hint", "请告诉我要发布哪个产品的供需信息，例如「帮我求购PP 30吨」。"), tool, 0);
                }
                // ⭐ 发布前逐行预过滤：把安全品种与危化品品种拆开 ——
                // 安全品种照常发布，危化品品种不发布并在回复里标红提示（2026-09-16 用户要求）。
                java.util.List<Map<String, Object>> safeItems = new java.util.ArrayList<>();
                java.util.List<String> blockedNames = new java.util.ArrayList<>();
                java.util.List<Map<String, Object>> blockedItems = new java.util.ArrayList<>();
                for (Map<String, Object> it : items) {
                    String nm = String.valueOf(it.get("name"));
                    String hit = demandService.dangerousHitName(nm);
                    if (hit == null) {
                        safeItems.add(it);
                    } else {
                        if (!blockedNames.contains(hit)) blockedNames.add(hit);
                        blockedItems.add(it);
                    }
                }
                // ⭐ 第 2 层：AI 语义判定（覆盖拼音/谐音/俗称/化学式，关键词匹配不到的那些）
                // 只对「关键词没拦下」的品种做二次判断，避免白烧 token。
                if (!safeItems.isEmpty()) {
                    java.util.List<String> pending = new java.util.ArrayList<>();
                    for (Map<String, Object> it : safeItems) {
                        pending.add(String.valueOf(it.get("name")));
                    }
                    Map<String, Map<String, Object>> aiRes = aiDangerousCheck(pending);
                    if (aiRes != null) {
                        java.util.List<Map<String, Object>> stillSafe = new java.util.ArrayList<>();
                        for (Map<String, Object> it : safeItems) {
                            String nm = String.valueOf(it.get("name"));
                            Map<String, Object> verdict = aiRes.get(nm);
                            boolean dangerous = verdict != null
                                    && Boolean.TRUE.equals(verdict.get("dangerous"));
                            boolean uncertain = verdict != null
                                    && Boolean.TRUE.equals(verdict.get("uncertain"));
                            if (dangerous) {
                                // 明确判定为危化品 -> 拦截
                                String canonical = verdict.get("canonical") == null
                                        ? "" : String.valueOf(verdict.get("canonical"));
                                it.put("_blockReason", canonical.isEmpty() ? nm : canonical);
                                it.put("_blockSource", "AI语义判定");
                                String hit = canonical.isEmpty() ? nm : canonical;
                                if (!blockedNames.contains(hit)) blockedNames.add(hit);
                                blockedItems.add(it);
                                log.info("危化品AI语义拦截：{} -> {}（{}）", nm, canonical,
                                        verdict.get("reason"));
                            } else {
                                // ⚠️ uncertain 不拦截：精细化工品（如全氟己基乙基碘）常被判不确定，
                                // 一律拦住会大量误杀合法交易。放行 + 打日志留痕供管理员复核。
                                if (uncertain) {
                                    log.warn("危化品待复核（已放行）：{} -> {}（{}）", nm,
                                            verdict.get("canonical"), verdict.get("reason"));
                                }
                                stillSafe.add(it);
                            }
                        }
                        safeItems = stillSafe;
                    } else {
                        log.warn("危化品AI语义判定不可用，本单退回关键词判定结果");
                    }
                }

                // 全被拦 -> 不发布，直接给出标红提示
                if (safeItems.isEmpty()) {
                    StringBuilder b = new StringBuilder();
                    b.append("以下品种属于危险化学品/易制毒易制爆品类，平台禁止发布，本次未发布任何信息：\n");
                    for (Map<String, Object> it : blockedItems) {
                        b.append("　✕ ").append(it.get("name"));
                        Object why = it.get("_blockReason");
                        if (why != null && !String.valueOf(why).equals(String.valueOf(it.get("name")))) {
                            b.append("（即 ").append(why).append("）");
                        }
                        if (it.get("quantity") != null) {
                            b.append("｜").append(it.get("quantity"))
                             .append(it.get("unit") == null ? unit : it.get("unit"));
                        }
                        b.append('\n');
                    }
                    b.append("如确有需要，可到「供需广场」手动发布其他合规品种。");
                    Map<String, Object> df = new LinkedHashMap<>();
                    df.put("kind", "blocked");
                    df.put("reply", b.toString().trim());
                    df.put("blocked", blockedItems);
                    df.put("actions", List.of(Map.of("label", "去供需广场", "to", "/supply-demand")));
                    return okResp(df, tool, 0);
                }
                boolean partial = !blockedItems.isEmpty();
                items = safeItems;   // 后面只处理安全品种
                // 标题：取第一个品种作为主标题，多品种时补「等N个品种」
                String mainName = String.valueOf(items.get(0).get("name"));
                String firstQty = items.get(0).get("quantity") == null
                        ? "" : String.valueOf(items.get(0).get("quantity"));
                String title = DemandService.buildTitle(type, mainName, firstQty, unit);
                if (items.size() > 1) {
                    title = title + " 等 " + items.size() + " 个品种";
                    if (title.length() > 120) title = title.substring(0, 120);
                } else if (partial) {
                    // 部分品种被拦：标题补一句，避免用户以为只发了一个品种
                    title = title + "（其余品种因合规原因未发布）";
                    if (title.length() > 120) title = title.substring(0, 120);
                }
                // 拼接全部品种名（与表单发布保持一致）
                StringBuilder allNames = new StringBuilder();
                for (Map<String, Object> it : items) {
                    if (allNames.length() > 0) allNames.append('、');
                    allNames.append(it.get("name"));
                }

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("type", type);
                body.put("title", title);
                body.put("items", items);
                body.put("varietiesName", allNames.toString());
                if (!qty.isEmpty()) body.put("quantity", qty);
                body.put("unit", unit);
                if (!price.isEmpty()) body.put("expectPrice", price);
                if (region != null && !region.isBlank()) body.put("region", region.trim());
                // 联系方式：优先用「用户上次发布实际填写的」（defaultContact），缺失才回退账号资料。
                // 这样 AI 代发不会用错联系人（实测用户常自定义联系人名，与昵称不同）。
                String[] dc = demandService.defaultContactOf(u.getId());
                String cName = dc[0].isBlank()
                        ? ((u.getNickname() == null || u.getNickname().isBlank())
                            ? u.getUsername() : u.getNickname())
                        : dc[0];
                String cPhone = dc[1].isBlank() ? demandService.phoneOf(u.getId()) : dc[1];
                String cEmail = dc[2].isBlank() ? u.getEmail() : dc[2];
                body.put("contactName", cName);
                body.put("contactEmail", cEmail);
                // 手机号必填：默认联系方式 > 账号绑定手机号
                body.put("contactPhone", cPhone);

                Result<Map<String, Object>> res = demandService.publish(u, body);
                if (res.getCode() != 200) {
                    String msg = res.getMessage();
                    String hint;
                    if (msg != null && msg.contains("手机号")) {
                        hint = "你的账号还没有绑定手机号，无法自动填写联系方式。"
                             + "请点击右上角头像绑定手机号，绑定后在这里说一句就能直接发布"
                             + "（也可以到「供需广场」手动发布，首次发布会自动绑定）。";
                    } else if (msg != null && msg.contains("数量")) {
                        hint = "求购信息需要填写数量，供应商要据此判断能否接单。"
                             + "请在对话里补充数量后再说一次，例如「求购 500 吨 葡萄糖酸钠」"
                             + "（也可以到「供需广场」手动填写）。";
                    } else {
                        hint = (msg == null ? "发布失败" : msg) + " 也可以到「供需广场」手动发布。";
                    }
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("kind", "clarify");
                    d.put("reply", "");
                    d.put("hint", hint);
                    d.put("actions", List.of(Map.of("label", "去供需广场", "to", "/supply-demand")));
                    return okResp(d, tool, 0);
                }

                String masked = DemandService.maskEmail(cEmail);
                if (masked == null) masked = "（未填写）";
                StringBuilder reply = new StringBuilder();
                reply.append("demand".equals(type) ? "已为你发布一条求购信息" : "已为你发布一条供应信息").append("：\n");
                reply.append("· 标题：").append(title).append('\n');
                if (items.size() == 1) {
                    Map<String, Object> only = items.get(0);
                    reply.append("· 产品：").append(only.get("name"));
                    if (only.get("quantity") != null) {
                        reply.append("｜数量：").append(only.get("quantity")).append(unit);
                    }
                    if (only.get("expectPrice") != null) {
                        reply.append("｜期望价：").append(only.get("expectPrice")).append(" 元/").append(unit);
                    }
                    reply.append('\n');
                } else {
                    reply.append("· 明细（共 ").append(items.size()).append(" 行）：\n");
                    for (Map<String, Object> it : items) {
                        reply.append("　- ").append(it.get("name"));
                        if (it.get("quantity") != null) {
                            reply.append("｜").append(it.get("quantity"))
                                 .append(it.get("unit") == null ? unit : it.get("unit"));
                        }
                        if (it.get("spec") != null) reply.append("｜").append(it.get("spec"));
                        if (it.get("expectPrice") != null) {
                            reply.append("｜").append(it.get("expectPrice")).append(" 元/")
                                 .append(it.get("unit") == null ? unit : it.get("unit"));
                        }
                        reply.append('\n');
                    }
                }
                reply.append("· 联系方式：").append(masked).append("（已按平台规则脱敏，仅认证供应商可见）\n");
                reply.append("· 有效期 30 天，可到「供需广场 → 我的发布」随时下架。");
                // 部分品种被拦：正文只列安全品种，被拦品种走 blocked 字段由前端标红
                if (partial) {
                    reply.append("\n· 已跳过 ").append(blockedItems.size())
                         .append(" 个不合规品种（见下方红色提示）。");
                }

                Map<String, Object> d2 = new LinkedHashMap<>();
                d2.put("kind", "answer");
                d2.put("reply", reply.toString());
                if (partial) d2.put("blocked", blockedItems);
                d2.put("actions", List.of(Map.of("label", "去供需广场查看", "to", "/supply-demand")));
                return okResp(d2, tool, 1);
            }
            case "chem_property", "chem", "chemistry", "property", "physicochemical": {
                String pn = argText(args, "product", "");
                if (pn.isBlank()) {
                    return okResp(ansResp("请告诉我要查哪个化学品的物性（如：甲醇、苯乙烯、冰醋酸）。"), tool, 1);
                }
                String summary = chemController.aiSummary(pn);
                if (summary == null) {
                    // ⭐ 未收录：明确告知，绝不让模型编造物性数值
                    Map<String, Object> dn = ansResp(
                            "数据库暂未收录「" + pn + "」的物性资料。\n\n"
                          + "常见原因：该品种属于混合物、复配产品或制品，没有单一化合物标识；或尚未纳入物性库。\n"
                          + "你可以：\n"
                          + "· 换用更规范的化学品名再问一次（如「甲醇」「苯乙烯」「冰醋酸」）\n"
                          + "· 到「物性查询」页用权威数据源继续检索");
                    dn.put("actions", List.of(Map.of("label", "去物性查询页检索", "to", "/chem-data")));
                    return okResp(dn, tool, 1);
                }
                Map<String, Object> dc = ansResp(summary);
                dc.put("actions", List.of(Map.of("label", "打开物性查询页", "to", "/chem-data?q=" + pn)));
                return okResp(dc, tool, 1);
            }
            case "platform_help", "help", "faq", "contact", "about": {
                return okResp(ansResp(platformHelp(message)), tool, 1);
            }
            default:
                return okResp(Map.of("kind", "clarify", "reply", "",
                        "hint", clarifyText != null && !clarifyText.isBlank()
                                ? clarifyText : "暂不支持这个查询维度，试试问价格、走势、排行、综合概览或参考解读。"), "none", 0);
        }
    }

    /* ================= 商品名对齐 + 权限判定 ================= */

    /* ================= 意图兜底：从自然语言里扒商品名 ================= */

    /** 价格类问法（命中才考虑兜底） */
    private static final Pattern PRICE_WORDS = Pattern.compile(
            "最新价格|最新报价|最新价|价格是多少|报价是多少|报价多少|多少钱一吨|多少钱|什么价格|什么价"
                    + "|的报价|的均价|均价|报价|价格|行情|现价|单价|报个价");

    /** 需要剔除的噪音词（去掉后剩下的就是商品名候选） */
    private static final Pattern NOISE_WORDS = Pattern.compile(
            "请问|帮我|帮忙|查一下|查询一下|看一下|告诉我|我想知道|想知道|现在|目前|一下|一吨"
                    + "|多少|呢|啊|吧|嘛|了|的|是|给|我|怎么样|怎样|如何");

    /**
     * 从问句里猜商品名：去掉价格词与噪音词，拿剩下的片段去库里匹配；匹配不到返回 null（不兜底）。
     * 例：「甲醇最新价格」→ 甲醇；「PP 现在多少钱一吨」→ PP。
     */
    private String guessProduct(String message) {
        if (message == null) return null;
        String m = message.trim();
        if (m.isEmpty() || !PRICE_WORDS.matcher(m).find()) return null;
        String s = PRICE_WORDS.matcher(m).replaceAll("");
        s = NOISE_WORDS.matcher(s).replaceAll("");
        s = s.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "");
        if (s.length() < 2 || s.length() > 12) return null;
        List<Map<String, Object>> cands = queryService.searchProducts(s);
        if (cands == null || cands.isEmpty()) return null;
        for (Map<String, Object> c : cands) {
            if (s.equals(String.valueOf(c.get("name")))) return s;
        }
        return String.valueOf(cands.get(0).get("name"));
    }

    private ProductRef resolveProduct(JsonNode args, boolean isAdmin, List<Integer> permitted, String message) {
        String kw = argText(args, "product", "");
        if (kw.isBlank()) {
            return ProductRef.clarify("请告诉我要查询哪个商品（如：丙烯、甲醇）。");
        }
        List<Map<String, Object>> cands = queryService.searchProducts(kw);
        if (cands.isEmpty()) {
            return ProductRef.error("数据库暂未收录「" + kw + "」，请换个商品名试试。");
        }
        // 按权限过滤
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> c : cands) {
            int vid = ((Number) c.get("varieties_id")).intValue();
            if (isAdmin || permitted.contains(vid)) visible.add(c);
        }
        if (visible.isEmpty()) {
            return ProductRef.error("「" + String.valueOf(cands.get(0).get("name")) + "」不在你的数据授权范围内，可联系管理员开通后再查询。");
        }
        // 精确全名命中优先（如用户说「丙烯」且库中有「丙烯」），避免同名包含歧义
        for (Map<String, Object> c : visible) {
            if (kw.equals(String.valueOf(c.get("name")))) {
                return ProductRef.ok(((Number) c.get("varieties_id")).intValue(),
                        String.valueOf(c.get("name")), c.get("unit") == null ? "" : String.valueOf(c.get("unit")));
            }
        }
        if (visible.size() > 1) {
            List<String> options = new ArrayList<>();
            for (Map<String, Object> c : visible) {
                options.add(String.valueOf(c.get("name")));
            }
            // 为每个候选生成「延续原问题意图」的续问：把问句中的产品词换成所选候选
            List<String> reasks = new ArrayList<>();
            String orig = message == null ? "" : message.trim();
            for (String name : options) {
                if (!orig.isEmpty() && !kw.isEmpty() && orig.contains(kw)) {
                    int idx = orig.indexOf(kw);
                    reasks.add((orig.substring(0, idx) + name + orig.substring(idx + kw.length())).trim());
                } else {
                    reasks.add((name + " " + orig).trim());
                }
            }
            return ProductRef.clarify("「" + kw + "」匹配到多个品种，请问你指的是哪一个？", options, reasks);
        }
        Map<String, Object> hit = visible.get(0);
        return ProductRef.ok(((Number) hit.get("varieties_id")).intValue(),
                String.valueOf(hit.get("name")), hit.get("unit") == null ? "" : String.valueOf(hit.get("unit")));
    }

    /* ================= 小工具方法 ================= */

    /**
     * 记录一次 AI 问答：问题、回答、命中工具、Token 用量、耗时。
     * <p>管理员可在「AI 问答记录」页查看（仅 ADMIN 可访问）。写日志失败不影响给用户的回答。
     */
    private void saveAiLog(Long userId, String username, String question, String answer,
                           String tool, String kind, int[] tk, int costMs) {
        try {
            jdbcTemplate.update(
                "INSERT INTO ai_chat_log (user_id, username, question, answer, tool, kind, model, " +
                "prompt_tokens, completion_tokens, total_tokens, cost_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                userId, username, clip(question, 600),
                clip(answer == null ? "" : answer, 20000), clip(tool, 32), clip(kind, 16),
                mimoClient.modelName(), tk[0], tk[1], tk[2], costMs);
        } catch (Exception e) {
            log.warn("写 AI 问答记录失败: {}", e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 查不到数据时的回应：区分「该品种本身没数据」与「用户指定的地区没数据」。
     * 后者会列出该品种实际收录的报价点（实测：PP 没有「山东」报价点，但有华东/华南/青岛等 48 个），
     * 否则用户会把"这个地区没数据"理解成"这个品种没数据"。
     */
    private Map<String, Object> noDataResp(ProductRef p, String type, String market,
                                           boolean isAdmin, Long userId) {
        if (market == null || market.isBlank()) return p.noData(type);
        List<String> ms = queryService.marketOptions(p.id(), type, isAdmin, userId, 6);
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(p.name()).append("」在「").append(market).append("」暂无已收录的报价数据。");
        if (!ms.isEmpty()) {
            sb.append("该品种目前收录的报价点包括：").append(String.join("、", ms))
              .append(" 等。可以换个地区再问，也可以不指定地区——我按全市场均价给你看。");
        }
        return okResp(Map.of("kind", "error", "hint", "NO_DATA_MARKET", "reply", sb.toString()), "none", 0);
    }

    private String trendSummary(List<Map<String, Object>> series) {
        double first = (Double) series.get(0).get("price");
        Map<String, Object> last = series.get(series.size() - 1);
        double lastP = (Double) last.get("price");
        double max = first, min = first;
        String maxD = String.valueOf(series.get(0).get("date")), minD = maxD;
        for (Map<String, Object> s : series) {
            double v = (Double) s.get("price");
            if (v > max) { max = v; maxD = String.valueOf(s.get("date")); }
            if (v < min) { min = v; minD = String.valueOf(s.get("date")); }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("起始价(首日)", first);
        m.put("起始日期", series.get(0).get("date"));
        m.put("最新价", lastP);
        m.put("最新日期", last.get("date"));
        m.put("最新涨跌额", last.get("change"));
        m.put("区间最高", max + "（" + maxD + "）");
        m.put("区间最低", min + "（" + minD + "）");
        return jsonOf(m);
    }

    /** 统一的"纯文本回答"响应体 */
    private Map<String, Object> ansResp(String reply) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "answer");
        m.put("reply", reply);
        m.put("chart", null);
        m.put("quotes", null);
        return m;
    }

    /**
     * 导出专用产品权限预检：返回被拒的品种名（无权限时），有权限或无法匹配时返回 null。
     * 与 resolveProduct 的区别只是提示语——导出场景要明确告诉用户"没有该产品权限"。
     */
    private String deniedProductName(JsonNode args, boolean isAdmin, List<Integer> permitted) {
        String kw = argText(args, "product", "");
        if (kw.isBlank()) return null;
        List<Map<String, Object>> cands = queryService.searchProducts(kw);
        if (cands.isEmpty()) return null;
        for (Map<String, Object> c : cands) {
            Object vid = c.get("varieties_id");
            if (vid == null) continue;
            if (isAdmin || permitted.contains(((Number) vid).intValue())) return null;
        }
        return String.valueOf(cands.get(0).get("name"));
    }

    /* ================= 服务端可信事实清单（模型只能转述，不能自造数字） ================= */

    /** 紧凑涨跌描述（数据包内用，省 token）：涨50 / 跌50 / 平 */
    private static String compactChg(Object chg) {
        if (chg == null) return "-";
        double c = toD(chg);
        if (c > 0) return "涨" + fmtNum(c);
        if (c < 0) return "跌" + fmtNum(-c);
        return "平";
    }

    private static double toD(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private List<String> latestFacts(ProductRef p, List<Map<String, Object>> rows, Map<String, Object> r) {
        List<String> f = new ArrayList<>();
        String unit = rows.get(0).get("unit") == null ? "" : String.valueOf(rows.get(0).get("unit"));
        String name = p.name();
        String date = String.valueOf(rows.get(0).get("date"));
        String label = String.valueOf(r.getOrDefault("tableLabel", ""));
        boolean sameDate = rows.stream().allMatch(x -> date.equals(String.valueOf(x.get("date"))));

        if (rows.size() == 1) {
            Map<String, Object> q = rows.get(0);
            f.add(name + "（" + label + "）最新报价：" + q.get("market")
                    + (isBlank(q.get("spec")) ? "" : " " + q.get("spec"))
                    + " = " + fmtNum(q.get("price")) + " " + unit
                    + "；报价日期 " + date + "；较上一交易日" + chgDesc(q.get("change"), q.get("rate")));
        } else if (sameDate) {
            // 紧凑格式（省 token 但报价点一个不少，模型仍能看到全部市场；单位与格式在表头统一交代）
            f.add(name + "（" + label + "）最新交易日 " + date + "，共 " + rows.size()
                    + " 个报价点。单位：" + unit + "。格式：报价点（规格） 价格 涨跌");
            for (Map<String, Object> q : rows) {
                f.add("·" + q.get("market") + (isBlank(q.get("spec")) ? "" : "（" + q.get("spec") + "）")
                        + " " + fmtNum(q.get("price")) + " " + compactChg(q.get("change")));
            }
        } else {
            f.add(name + "（" + label + "）共 " + rows.size() + " 条报价。单位：" + unit
                    + "。格式：报价点（规格） 日期 价格 涨跌");
            for (Map<String, Object> q : rows) {
                f.add("·" + q.get("market") + (isBlank(q.get("spec")) ? "" : "（" + q.get("spec") + "）")
                        + " " + q.get("date") + " " + fmtNum(q.get("price")) + " " + compactChg(q.get("change")));
            }
        }
        return f;
    }

    @SuppressWarnings("unchecked")
    private List<String> overviewFacts(ProductRef p, Map<String, Object> r) {
        List<String> f = new ArrayList<>();
        String unit = r.get("unit") == null ? "" : String.valueOf(r.get("unit"));
        f.add(p.name() + "（" + r.get("tableLabel") + "）主报价点：" + r.get("market") + "，单位 " + (unit.isEmpty() ? "—" : unit));

        Map<String, Object> latest = (Map<String, Object>) r.get("latest");
        if (latest != null) {
            f.add("【最新价，权威】" + latest.get("market") + " = " + fmtNum(latest.get("price")) + " " + unit
                    + "，日期 " + latest.get("date") + "，" + chgDesc(latest.get("change"), latest.get("rate")));
        }
        Map<String, Object> st = (Map<String, Object>) r.get("stats6m");
        if (st != null && !st.isEmpty()) {
            f.add("近6月统计（仅历史区间，不是现价）：最高 " + fmtNum(st.get("maxPrice")) + "，最低 "
                    + fmtNum(st.get("minPrice")) + "，均价 " + fmtNum(st.get("avgPrice"))
                    + "，数据日 " + fmtNum(st.get("totalDays")) + " 天" + (st.get("stdDev") == null ? "" : "，标准差 " + fmtNum(st.get("stdDev"))));
        }
        List<Map<String, Object>> monthly = (List<Map<String, Object>>) r.getOrDefault("monthly12", List.of());
        if (!monthly.isEmpty()) {
            f.add("近12月月度（月份：月均/月内最高/月内最低）：");
            for (Map<String, Object> m : monthly) {
                f.add("· " + m.get("month") + "：" + fmtNum(m.get("avgPrice")) + " / "
                        + fmtNum(m.get("highPrice")) + " / " + fmtNum(m.get("lowPrice")));
            }
        }
        List<Map<String, Object>> rf = (List<Map<String, Object>>) r.getOrDefault("riseFall6m", List.of());
        if (!rf.isEmpty()) {
            StringBuilder sb = new StringBuilder("近6月涨跌频率：");
            for (Map<String, Object> m : rf) {
                String dir = "up".equals(m.get("dir")) ? "上涨" : "down".equals(m.get("dir")) ? "下跌" : "持平";
                sb.append(dir).append(" ").append(fmtNum(m.get("cnt"))).append(" 天")
                        .append(m.get("avgChg") == null ? "" : "（平均" + fmtNum(m.get("avgChg")) + "）").append("；");
            }
            f.add(sb.toString());
        }
        List<Map<String, Object>> peers = (List<Map<String, Object>>) r.getOrDefault("peers", List.of());
        if (!peers.isEmpty()) {
            f.add("其他市场近6月均价（仅横向对比历史均价，不是最新价）：");
            for (Map<String, Object> m : peers) {
                f.add("· " + m.get("market_name") + " 均价 " + fmtNum(m.get("avgPrice")) + "（约 "
                        + fmtNum(m.get("days")) + " 天）");
            }
        }
        return f;
    }

    @SuppressWarnings("unchecked")
    private List<String> outlookFacts(ProductRef p, Map<String, Object> r) {
        List<String> f = new ArrayList<>();
        String unit = r.get("unit") == null ? "" : String.valueOf(r.get("unit"));
        Map<String, Object> sig = (Map<String, Object>) r.get("signal");
        Map<String, Object> ind = (Map<String, Object>) r.get("indicators");
        String win = sig == null ? "近若干交易日" : String.valueOf(sig.getOrDefault("window", ""));
        String asof = sig == null ? "" : String.valueOf(sig.getOrDefault("asof", ""));

        f.add(p.name() + "（" + r.get("tableLabel") + "）主报价点：" + r.get("market") + "，单位 "
                + (unit.isEmpty() ? "—" : unit) + "；统计窗口 " + win + "（数据截至 " + asof + "）");

        Map<String, Object> latest = (Map<String, Object>) r.get("latest");
        if (latest != null) {
            f.add("【最新价，权威】" + latest.get("market") + " = " + fmtNum(latest.get("price")) + " " + unit
                    + "，日期 " + latest.get("date") + "，" + chgDesc(latest.get("change"), latest.get("rate")));
        }
        if (sig != null) {
            int score = ((Number) sig.get("score")).intValue();
            String bias = String.valueOf(sig.get("bias"));
            String strength = String.valueOf(sig.get("strength"));
            f.add("【服务端综合信号结论】方向倾向：" + bias + "（" + strength + "），信号分 "
                    + (score > 0 ? "+" : "") + score + "/±5（正=偏多，负=偏空）");
        }
        if (ind != null) {
            Object ma5 = ind.get("ma5"), ma10 = ind.get("ma10"), ma20 = ind.get("ma20");
            f.add("均线参考：MA5=" + fmtNum(ma5) + "，MA10=" + fmtNum(ma10) + "，MA20=" + fmtNum(ma20)
                    + (ma10 instanceof Number a && ma20 instanceof Number b
                    ? (a.doubleValue() >= b.doubleValue() ? "（短期均线在上方，多头排列）" : "（短期均线在下方，空头排列）") : ""));
            Object ret5 = ind.get("ret5"), ret20 = ind.get("ret20");
            f.add("动量参考：近5日 " + (ret5 == null ? "-" : fmtNum(ret5) + "%") + "，近20日 "
                    + (ret20 == null ? "-" : fmtNum(ret20) + "%"));
            Object rp = ind.get("rangePct");
            if (rp != null) {
                f.add("区间位置：当前价处于统计窗口区间约 " + fmtNum(rp) + "% 分位");
            }
            Object su = ind.get("streakUp"), sd2 = ind.get("streakDays");
            if (sd2 != null) {
                boolean isUp = !(su instanceof Boolean b) || b;
                f.add("连涨连跌：当前已连续" + (isUp ? "上涨" : "下跌") + " " + fmtNum(sd2) + " 个交易日");
            }
        }
        Object view = r.get("view");
        if (view != null && !String.valueOf(view).isBlank() && !"null".equals(String.valueOf(view))) {
            f.add("信号依据（服务端规则生成，逐条可复核）：" + view);
        }
        return f;
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty();
    }

    private static String chgDesc(Object chg, Object rate) {
        if (chg == null && rate == null) return "无涨跌数据";
        double c = chg == null ? 0 : ((Number) chg).doubleValue();
        StringBuilder sb = new StringBuilder();
        if (c == 0) sb.append("持平");
        else sb.append(c > 0 ? "上涨 " : "下跌 ").append(fmtNum(Math.abs(c))).append(" 元/吨");
        if (rate != null) sb.append("（幅度 ").append(fmtNum(rate)).append("%）");
        return sb.toString();
    }

    private static String fmtNum(Object v) {
        if (v == null) return "";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "";
            if (d == Math.rint(d)) return String.valueOf((long) d);
            return String.valueOf(Math.round(d * 100.0) / 100.0);
        }
        return String.valueOf(v);
    }

    private Map<String, Object> chartOf(Map<String, Object> r, ProductRef p, String type) {
        @SuppressWarnings("unchecked")
        Map<String, Object> chart = (Map<String, Object>) r.get("chart");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", p.name() + " · " + r.get("market") + " · " + r.get("tableLabel"));
        c.put("dates", chart.get("dates"));
        c.put("values", chart.get("values"));
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.getOrDefault("rows", List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> seriesOf(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.getOrDefault("series", List.of());
    }

    private Map<String, Object> okResp(Map<String, Object> data, String tool, int rows) {
        Map<String, Object> m = new LinkedHashMap<>(data);
        m.put("_tool", tool);
        m.put("_rows", rows);
        return m;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        return auth.getName();
    }

    /**
     * 回答后处理：模型偶尔会违反「不要用 Markdown」的约定（实测会出现 **加粗**、# 标题、`反引号`），
     * 前端与公众号都按纯文本渲染，会把这些符号原样显示出来，所以在这里强制清除。
     */
    private static String cleanAnswer(String s) {
        if (s == null) return null;
        return s.replace("**", "")
                .replace("`", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "· ")
                .trim();
    }

    private String jsonOf(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /** 剥离 Markdown 围栏后解析为 JSON 对象，失败返回 null */
    private JsonNode parseJsonObject(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3).trim();
        }
        try {
            JsonNode node = objectMapper.readTree(s);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            // 兜底：截取首尾花括号之间的内容
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a >= 0 && b > a) {
                try {
                    JsonNode node = objectMapper.readTree(s.substring(a, b + 1));
                    return node != null && node.isObject() ? node : null;
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    private static String argText(JsonNode args, String key, String dft) {
        if (args == null || !args.hasNonNull(key)) return dft;
        String v = args.path(key).asText("").trim();
        return v.isEmpty() ? dft : v;
    }

    private static String nullableArg(JsonNode args, String key) {
        if (args == null || !args.hasNonNull(key)) return null;
        String v = args.path(key).asText("").trim();
        return v.isEmpty() ? null : v;
    }

    /* ================= 危化品语义判定（第 2 层防护） ================= */

    /** 语义判定提示词：只判「品类身份」，不判数量/用途 */
    private static final String DANGER_SYS = """
            你是化工品合规审核助手。用户会给你一个「品种名称」列表，这些名称可能来自用户口述或AI识别，
            可能写成拼音（jiacun）、谐音错字（甲荃）、俗称（木醇/酒精）、英文、化学式（CH3OH）或带包装描述。
            请对每个名称判断：它是否指向**危险化学品 / 易制毒化学品 / 易制爆化学品**（如甲醇、甲醛、苯、甲苯、
            丙酮、盐酸、硫酸、硝酸、液氨、液氯、双氧水、乙炔、氢气、氰化物、炸药类等）。

            判定要点：
            1. 先还原它最可能指向的真实化学品，再判断该化学品是否属于上述管制品类；
            2. 拼音/谐音/俗称/化学式都要还原后判断（如 jiacun→甲醇、甲荃→甲醛、木醇→甲醇、CH3OH→甲醇）；
            3. 判定标准是「是否列入国家危险化学品目录/易制毒易制爆管控」，**不是**「是否有害/有腐蚀性」。
               普通塑料/树脂/常规化工品（PP、PE、PVC、ABS、PS、PET、EVA、橡胶、涂料原料、常规助剂、
               精细化工中间体、含氟/含硅的特种化学品等）**不是**危化品 —— 不要因为名字吓人就判 dangerous；
            4. 只有当某物质**明确**属于目录管控品类（甲醇/甲醛/苯系/丙酮/强酸强碱/液氨液氯/双氧水/
               乙炔氢气/氰化物/炸药类等）时才填 dangerous=true；
            5. 名称看起来是**具体商品牌号、型号或某公司的产品代号**（如「神华L5E89」「裕龙1100N」）时，
               按普通商品处理，dangerous=false；
            6. 只有真正无法判断指向哪种物质、且可能涉管控时，才填 uncertain=true；能判断为普通商品就不要填。

            只输出 JSON，格式：
            {"items":[{"raw":"原始名称","canonical":"还原后的化学品名","dangerous":true,"uncertain":false,"reason":"简短理由"}]}
            不要输出任何解释文字或 Markdown。""";

    /**
     * 用 AI 对候选品种名做语义级危化品判定（覆盖拼音/谐音/俗称/化学式）。
     * 返回 Map(raw -> [canonical, dangerous, uncertain, reason])；AI 不可用或解析失败返回 null。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> aiDangerousCheck(java.util.List<String> names) {
        if (!demandService.dangerousCheckEnabled()) return null;   // 总开关关闭：不判定、不拦截
        if (names == null || names.isEmpty() || !mimoClient.isConfigured()) return null;
        StringBuilder q = new StringBuilder("请判断以下品种名称：\n");
        for (String n : names) q.append("- ").append(n).append('\n');
        String raw;
        try {
            raw = mimoClient.chatFast(DANGER_SYS, q.toString(), 900);
        } catch (Exception e) {
            log.warn("危化品语义判定调用失败：{}", e.getMessage());
            return null;
        }
        JsonNode root = parseJsonObject(raw);
        if (root == null || !root.path("items").isArray()) return null;
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (JsonNode n : root.path("items")) {
            String r = n.path("raw").asText("").trim();
            if (r.isEmpty()) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("canonical", n.path("canonical").asText("").trim());
            m.put("dangerous", n.path("dangerous").asBoolean(false));
            m.put("uncertain", n.path("uncertain").asBoolean(false));
            m.put("reason", n.path("reason").asText("").trim());
            out.put(r, m);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 解析 AI 返回的供货明细。
     * 兼容两种形态：
     *   ① 新：items = [{name, quantity, unit, spec, expectPrice}, ...]
     *   ② 老：product = "PP"（可含顿号/逗号分隔的多品种）+ quantity/unit/expectPrice
     * 返回 null 表示没有可用明细。
     */
    /** AI 代发供需总开关（site_setting: ai_publish_enabled；缺省=开，仅 '0' 关闭） */
    private boolean aiPublishEnabled() {
        try {
            String v = jdbcTemplate.queryForObject(
                    "SELECT setting_value FROM site_setting WHERE setting_key = 'ai_publish_enabled'",
                    String.class);
            return v == null || v.isBlank() || !"0".equals(v.trim());
        } catch (Exception e) {
            return true;
        }
    }

    private static java.util.List<Map<String, Object>> parseAiItems(
            JsonNode args, String product, String qty, String unit, String price, String spec) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();

        // ① items 数组优先
        JsonNode arr = args == null ? null : args.path("items");
        if (arr != null && arr.isArray() && arr.size() > 0) {
            for (JsonNode n : arr) {
                if (!n.isObject()) continue;
                String nm = n.path("name").asText("").trim();
                if (nm.isEmpty()) continue;
                Map<String, Object> it = new java.util.LinkedHashMap<>();
                it.put("name", nm);
                String q = n.path("quantity").asText("").trim();
                if (!q.isEmpty()) it.put("quantity", q);
                String u = n.path("unit").asText("").trim();
                it.put("unit", u.isEmpty() ? "吨" : u);
                String sp = n.path("spec").asText("").trim();
                if (!sp.isEmpty()) it.put("spec", sp);
                String p = n.path("expectPrice").asText("").trim();
                if (!p.isEmpty()) it.put("expectPrice", p);
                out.add(it);
                if (out.size() >= 50) break;
            }
            if (!out.isEmpty()) {
                // 去重兜底：AI 偶尔把多个牌号归并成完全相同的行（同 name/spec/数量/期望价），
                // 只保留一行，避免发布出多行一模一样的明细（2026-09-17 帖 #176 事故）
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                java.util.List<Map<String, Object>> dedup = new java.util.ArrayList<>();
                for (Map<String, Object> it : out) {
                    String key = it.get("name") + "|" + it.getOrDefault("spec", "") + "|"
                            + it.getOrDefault("quantity", "") + "|" + it.getOrDefault("expectPrice", "");
                    if (seen.add(key)) dedup.add(it);
                }
                return dedup;
            }
        }

        // ② 老的 product 字段：可能一个词，也可能是「PP、PE」
        if (product == null || product.isBlank()) return null;
        String[] names = product.split("[,，、;；/]+");
        java.util.List<String> clean = new java.util.ArrayList<>();
        for (String x : names) {
            String t = x.trim();
            if (!t.isEmpty()) clean.add(t);
        }
        if (clean.isEmpty()) return null;

        for (int i = 0; i < clean.size(); i++) {
            Map<String, Object> it = new java.util.LinkedHashMap<>();
            it.put("name", clean.get(i));
            it.put("unit", unit == null || unit.isBlank() ? "吨" : unit);
            // 数量/期望价/规格只给第一个品种（用户原话通常只针对主品种）
            if (i == 0) {
                if (qty != null && !qty.isBlank()) it.put("quantity", qty.trim());
                if (price != null && !price.isBlank()) it.put("expectPrice", price.trim());
                if (spec != null && !spec.isBlank()) it.put("spec", spec.trim());
            }
            out.add(it);
        }
        return out;
    }

    /** 平台使用类问题的标准答复（固定文案，不由模型自由发挥，避免编造收费等信息） */
    private static String platformHelp(String msg) {
        String m = msg == null ? "" : msg;
        if (hasAny(m, "客服", "联系", "电话", "邮箱", "微信", "公众号", "人工", "咨询")) {
            return "如需人工协助，可以通过以下方式联系我们：\n"
                 + "· 客服邮箱：" + SUPPORT_EMAIL + "（推荐，1 个工作日内回复）\n"
                 + "· 微信公众号：在网站首页扫码关注，关注后可直接在公众号里对话查询行情\n"
                 + "· 网站自助：左侧「邮件推送」可配置定时推送，「用户中心」可管理账号";
        }
        if (hasAny(m, "开通", "权限", "申请", "试用", "授权")) {
            return "平台按产品（品种）授权开通：\n"
                 + "· 已有账号但查不到某品种，说明该品种还没开通权限 —— 可联系管理员或发邮件到 " + SUPPORT_EMAIL + " 申请\n"
                 + "· 管理员可在「用户管理」中按单个商品或按大类批量开通\n"
                 + "· 开通后你可以直接问我「XX最新价格」「XX近30天走势」";
        }
        if (hasAny(m, "收费", "费用", "付费", "免费", "服务费", "方案")) {
            return "关于开通与费用：平台按产品授权开通，具体方案请联系客服 " + SUPPORT_EMAIL + "。\n"
                 + "如果你已经有账号和权限，直接告诉我品种名就能查行情。";
        }
        if (hasAny(m, "来源", "哪里", "更新", "频率", "覆盖", "几个品种", "多少品种", "数据从", "准确")) {
            return "数据说明：\n"
                 + "· 来源：ChemPrice 化工价格平台，覆盖全国主要产销区的现货报价\n"
                 + "· 采集：每个工作日 07:00-17:00，每 30 分钟自动采集一轮\n"
                 + "· 内容：市场价格（按地区）、企业价格（按厂家）、国际价格（以美元计价的外盘基准价）\n"
                 + "· 口径：主流价取报价区间中点，涨跌按中间价与上一交易日对比";
        }
        if (hasAny(m, "问价", "查价", "怎么查", "查询价格", "怎么问", "看价格", "查行情")) {
            return "问价用法：直接用大白话问我就行，不用记格式。\n"
                 + "· 查最新价 —— 「甲醇最新价格」「PP 今天多少钱」\n"
                 + "· 看走势 —— 「PP 近30天走势」「甲醇近7天走势」\n"
                 + "· 查涨跌排行 —— 「今天涨幅最大的品种」「跌幅 Top5」\n"
                 + "· 看地区报价 —— 「PP 各地区报价」「PP 山东多少钱」\n"
                 + "· 看综合概览 —— 「纯苯 的综合概览」\n"
                 + "· 看后市参考 —— 「甲醇后市怎么看」（只给历史统计，不做预测）\n"
                 + "支持一次问多个品种，如「PP 和 PE 今天多少钱」。";
        }
        if (hasAny(m, "发布", "求购", "供应", "上架", "发一条", "怎么发")) {
            return "发布用法：可以让我代发，也可以到页面手动发。\n"
                 + "· 一句话代发 —— 「帮我求购 PP 20吨」「我有一批 PE 要卖 30吨」\n"
                 + "  我会先识别品种与数量、写进「供需广场」，不会直接发出\n"
                 + "· 手动发布 —— 左侧「供需广场」右上角「发布供需信息」，支持一行一个品种\n"
                 + "· 认证要求 —— 发布求购需实名认证；发布供应需企业认证（均免费）\n"
                 + "· 数量限制 —— 每人每日最多 5 条，每条有效期 30 天，可随时下架\n"
                 + "· 发布后可在「供需广场 → 我的发布」里管理（下架 / 删除）";
        }
        if (hasAny(m, "推送", "订阅", "邮件通知", "定时")) {
            return "邮件推送设置方法：\n"
                 + "· 在左侧菜单进入「邮件推送」，可创建多个推送任务\n"
                 + "· 每个任务可单独设置任务名称、发送时间和要跟踪的商品\n"
                 + "· 到点后系统会把最新报价自动发到你的注册邮箱，也可以先「测试发送」预览效果";
        }
        if (hasAny(m, "导出", "excel", "下载", "表格")) {
            return "数据导出方法：直接对我说「导出XX近7天的数据」即可。\n"
                 + "· 生成的 Excel 包含市场价 / 企业价 / 国际价三个工作表\n"
                 + "· 单次最多 7 天，下载链接 30 分钟内有效\n"
                 + "· 需要你的账号具备该品种权限与导出权限";
        }
        return "我是 ChemPrice 的智能助手，可以帮你：\n"
             + "· 查最新报价 —— 试试「甲醇最新价格」\n"
             + "· 看价格走势 —— 试试「PP 近30天走势」\n"
             + "· 找涨跌机会 —— 试试「今天涨幅最大的品种」\n"
             + "· 解读行情趋势 —— 试试「甲醇后市怎么看」\n"
             + "· 导出数据 —— 试试「导出甲醇近7天数据」\n"
             + "需要人工协助可发邮件到 " + SUPPORT_EMAIL + "。";
    }

    private static boolean hasAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static int clampInt(JsonNode args, String key, int dft, int min, int max) {
        if (args == null || !args.hasNonNull(key)) return dft;
        int v = args.path(key).asInt(dft);
        return Math.max(min, Math.min(max, v));
    }

    /* ================= 商品引用解析结果 ================= */

    private record ProductRef(Integer id, String name, String unit, String kind, String hint, List<String> options, List<String> reasks) {
        static ProductRef ok(int id, String name, String unit) {
            return new ProductRef(id, name, unit, "ok", null, null, null);
        }
        static ProductRef clarify(String hint) {
            return new ProductRef(null, null, null, "clarify", hint, null, null);
        }
        static ProductRef clarify(String hint, List<String> options, List<String> reasks) {
            return new ProductRef(null, null, null, "clarify", hint, options, reasks);
        }
        static ProductRef error(String hint) {
            return new ProductRef(null, null, null, "error", hint, null, null);
        }
        boolean needClarify() {
            return !"ok".equals(kind);
        }
        Map<String, Object> toResp() {
            if ("ok".equals(kind)) return Map.of();
            String reply = "clarify".equals(kind)
                    ? (hint != null ? hint : "请补充说明你要查询的商品。")
                    : (hint != null ? hint : "无法查询该商品。");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "clarify".equals(kind) ? "clarify" : "error");
            m.put("reply", "");
            m.put("hint", reply);
            m.put("options", options == null ? null : options);
            m.put("reasks", reasks == null ? null : reasks);
            return m;
        }
        Map<String, Object> noData(String type) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "answer");
            m.put("reply", "「" + name + "」在" + ("enterprise".equals(type) ? "企业价格" :
                    "international".equals(type) ? "国际价格" : "市场价格") + "中暂无已收录的报价数据。");
            m.put("quotes", null);
            m.put("chart", null);
            return m;
        }
    }

    /* ================= 频控 ================= */

    private static final class RateLimiter {
        private final int max;
        private final long windowMs;
        private final Map<Long, Deque<Long>> hits = new ConcurrentHashMap<>();

        RateLimiter(int max, long windowMs) {
            this.max = max;
            this.windowMs = windowMs;
        }

        boolean allow(Long userId) {
            long now = System.currentTimeMillis();
            Deque<Long> q = hits.computeIfAbsent(userId, k -> new ArrayDeque<>());
            synchronized (q) {
                while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
                if (q.size() >= max) return false;
                q.addLast(now);
                return true;
            }
        }
    }

    /* ================= 提示词 ================= */

    private static final String INTENT_SYS = """
            你是 ChemPrice 化工价格数据平台的自然语言查询入口。用户会输入中文问题，请把它映射成唯一一个工具调用。

            可用工具：
            1. latest_price：查询某商品的最新报价。**只要用户在问某个具体商品的「价格」，一律用这个工具**，
               包括但不限于这些说法：多少钱 / 最新价格 / 最新报价 / 最新价 / 现在什么价 / 报价多少 /
               价格是多少 / 多少钱一吨 / 均价多少 / 报个价 / 现价 / 单价 / 价格怎么样 / 行情怎么样。
               判断要点：句子里能看出**是哪个商品**（如「甲醇最新价格」「PP 多少钱」）→ 用本工具。
               反例（不要用本工具）：只问「今天涨得最多的是哪些」→ rank_movers；
               「价格为什么涨」这类原因分析、与本站数据无关的问题 → none。
               args: { "product":"商品名", "type":"market|enterprise|international（默认 market）", "market":"报价点名称（如华东/山东，不确定可不填）" }
            2. price_trend：查询某商品近 N 天价格走势（会附带走势图）。
               args: { "product":"商品名", "type":"...", "market":"...", "days":30 }
               days 为用户要求的天数，**按用户原话如实填写、不要自行截断**：
               「今天/当天」填 1，「近7天」填 7，「近30天/近一个月」填 30，「近3个月」填 90，
               「近半年」填 180，「近一年/近1年/一年内」填 365。服务端单次走势查询上限为 365 天。
            3. price_overview：查询某商品综合概览：最新价、近6月统计、近12月月度走势、涨跌频率、多市场对比。
               args: { "product":"商品名", "type":"...", "market":"..." }
            4. rank_movers：查询最新交易日的涨跌排行。
               args: { "direction":"up|down|flat（默认 up）", "top_n":10, "type":"..." }
            5. trend_outlook：趋势信号解读。用户问「会不会涨/跌、未来走势、还能涨多久、现在该不该买/卖、行情如何看、预测」这类问题时使用。服务端基于真实历史价格序列计算 MA/动量/区间分位等指标并给出「偏多/偏空/震荡」方向倾向参考，不做未来点位预测。
               args: { "product":"商品名", "type":"...", "market":"..." }
            6. export_data：把某商品的**历史价格明细导出成 Excel**。用户说「导出/下载/要一份 Excel/表格/数据文件」时使用。
               args: { "product":"商品名", "days":7 }
               days 为用户要求的天数，**按用户原话如实填写、不要自行截断**：默认 7；「今天/当天」填 1，「近3天」填 3，
               「近30天/近一个月」填 30，「近3个月」填 90，「近半年」填 180，「近一年」填 365。
               服务端单次导出上限为 7 天，超过时会在回复中明确告知用户"已按 7 天导出"，不需要你改写数字。
               注意：只能导出一个商品；用户一次说了多个商品时，取第一个并让他确认。
            7. platform_help：回答**平台使用类**问题（不是查行情）。用户问「怎么联系客服 / 客服电话 / 邮箱是多少」
               「怎么开通权限 / 能不能试用 / 怎么收费」「数据来源是哪 / 更新频率 / 覆盖多少品种」
               「公众号是多少 / 怎么关注」「邮件推送怎么设置」「你是谁 / 你能做什么 / 怎么用」时使用。
               args: { "topic":"contact|permission|pricing|data|push|export|about" }
               注意：问「XX多少钱 / XX什么价 / XX最新价」属于查行情（latest_price），**不要**归入 platform_help。
            8. publish_demand：把用户的**求购/采购/供应/出售**意图发布成供需广场的一条真实信息。
               用户说「帮我求购XX」「我想买XX N吨」「求购XX，期望价XX」「供应XX」「出售XX」「我有XX现货」时使用。
               args: { "type":"demand|supply（求购/采购/想买=demand；供应/出售/现货=supply）",
                       "items":[ { "name":"商品名", "quantity":"数量（纯数字）", "unit":"吨",
                                   "spec":"规格/牌号（没说则不填）", "expectPrice":"期望单价（纯数字）" } ],
                       "quantity":"（可选，兼容单产品）", "unit":"吨（默认吨）",
                       "expectPrice":"（可选，兼容单产品）", "region":"地区（没说可不填）" }
               **多产品铁律**：用户一次说了多个商品（如「求购 PP 30吨、PE 50吨」），
               必须在 items 里**每个商品一个对象**，各带自己的数量/规格/期望价；
               **不要**只取第一个，也**不要**把多个商品塞进一个 name。
               只说一个商品时，items 也写成一个元素的数组（保持格式统一）。
               数量/规格/期望价用户没提就不填该字段（"name" 必填）。
               **牌号铁律**：用户原话里的「厂家+牌号」（如 裕龙T30S、神华L5E89、1100N、京博550J、中泰圣雄）
               是供需信息的核心识别信息，**一个都不能丢**：
               - name：填该牌号所属的平台标准品种名（如 T30S/550J/1100N 属聚丙烯 → 「聚丙烯」；L5E89 属聚乙烯 → 「聚乙烯」）
               - spec：**必须填牌号原文**（尽量带厂家，如「裕龙T30S」「神华L5E89」），禁止省略或改写
               - 例：「我可以供应裕龙T30S，神华L5E89，裕龙1100N 京博550J」→
                 items=[{"name":"聚丙烯","spec":"裕龙T30S"},{"name":"聚乙烯","spec":"神华L5E89"},
                        {"name":"聚丙烯","spec":"裕龙1100N"},{"name":"聚丙烯","spec":"京博550J"}]
               - 判断不出所属品种时：name 填最接近的品种、spec 保留原文；都判断不出 → name 直接用原文
               - 用户没提牌号时 spec 保持不填
               注意：这是**会真实发布**的操作——用户明确表达了求购/供应的内容才调用；
               **品种填写**：把用户提到的**所有品种**都写进 items，不要自行做任何合规判断（是否可发布由服务端决定）。
               ② **品种名一律填规范化中文名**：用户若用拼音（jiacun）、谐音错字（甲荃）、俗称（木醇/酒精）
               或化学式（CH3OH）表达，你要先还原成标准化学品名再填入 items（此规则对**全部**品种生效，不只危化品）。
               但**判断与拆分由服务端完成**，你的职责是：
               ① 无论品种是什么，**必须照常调用本工具**，并按用户原话把全部品种填进 items 数组；
               ② 不要拒绝发布请求、不要做合规判断 —— 服务端会处理。
               只是「问问价格」→ 用 latest_price，**不要**误判成发布；「怎么发布」这类用法问题 → platform_help。
            9. chem_property：查询某化学品的**物性资料 / 是不是危化品 / 运输要求**（不返回价格）。
               用户问「熔点/沸点/密度/闪点/蒸气压/溶解性是多少」「物性/物理性质/理化性质」
               「是不是危化品/危险品/有什么危险性」「UN 编号/危险类别/包装类别/GHS」
               「运输要求/怎么运输/禁配物/储存条件」「SDS/MSDS」时使用。
               args: { "product":"化学品名（规范化中文名，如 甲醇、苯乙烯、冰醋酸）" }
               注意：① 本工具**只给物性/安全/运输信息，不给价格** —— 只要用户在问"价格"就用 latest_price；
               ② 若该品种未收录物性，服务端会明确回「未收录」，你必须**如实转述**，
                  **严禁**凭自己的知识编造熔点/沸点/密度等任何数值；
               ③ 用户既要价格又要物性时，优先回价格（latest_price），并提示可到「物性查询」页看物性。

            输出约束（必须遵守）：
            - 只输出一个 JSON 对象，禁止任何多余文字、禁止 Markdown 代码块。
            - 格式：{"tool":"工具名","args":{...},"clarify":null}
            - items[].name 用数据库标准商品名或常见简称（如丙烯、甲醇、纯苯、PVC）。无法确定时按最可能的名称填，服务端会自动匹配并对歧义进行澄清。
            - 发布类（publish_demand）必须用 items 数组；查询类工具（latest_price 等）仍用 product 单字段，勿混用。
            - 「预测/会涨吗/会跌吗/未来价格」等问句一律归入 trend_outlook（服务端只返回历史统计参考，不预测），不要把这类问题判为不可查询。
            - 「导出/下载 Excel」一律归入 export_data（服务端会校验产品权限与导出权限），**不要**判为不可查询。
            - 「客服/联系方式/怎么开通/怎么收费/数据来源/更新频率/公众号/你是谁/怎么用」这类**平台使用问题**归入 platform_help，
              **不要**判为无法查询。
            - 「帮我求购XX / 我想供应XX / 出售XX」属于 publish_demand（**真实发布**，服务端会做认证与每日限频校验）；
              而「怎么发布 / 在哪发布」这类用法问题归 platform_help。
            - 「XX 怎么用 / XX 功能怎么用 / 怎么问价 / 怎么发布 / 怎么导出 / 在哪导出 / 能不能发布」这类
              **功能用法询问**一律归 platform_help —— 即使句中出现「发布 / 导出 / 求购 / 供应」等词，
              只要是在**问怎么操作**（而不是给出品种数量要求真实执行），就**不是** publish_demand / export_data。
            - 「熔点/沸点/密度/闪点/物性/是不是危化品/UN编号/危险类别/运输要求/禁配物/储存条件/GHS/SDS」
              这类**物性与安全类**问题一律归入 chem_property；**不要**因为它们与价格无关就判成 none。
            - 仅当问题要求因果原因分析（为什么涨/跌的深层原因）、外部新闻/政策信息，或与本站化工价格数据完全无关时：
              {"tool":"none","args":{},"clarify":"用一句中文说明为何无法查询，并给出可问的方向"}
            """;

    private static final String ANSWER_SYS = """
            你是 ChemPrice 化工价格数据平台的数据分析师。系统已按用户权限查询到真实数据（【查询数据】JSON）。
            请用简体中文回答用户，硬性规则：
            1. 只引用【查询数据】中真实存在的数值与日期；数据里没有的一律不得编造（不能自创日期/价格/数据）。
               **日期铁律**：数据包中的「最新交易日」字段（或每行的 date 字段）就是本次数据的日期。
               回答里若写日期，必须与它完全一致；**禁止**写成别的日期（例如数据日期是 2026-09-10，
               绝不能说成 9月9日）。若拿不准，就干脆不写日期，也不要猜。
               若数据包内含「方向倾向/信号分/偏多偏空」等综合信号结论，可据此给出概率性倾向解读（如「短期信号偏多」），但不得表述为确定性保证。
            2. 必须区分「最新价」与「历史统计值」：只有标注了“最新价”或“最新报价”的数据才是当前价格；AVG/min/max/monthly/peers 都是历史统计，描述时要说“近X月均价/区间最高最低”，绝不能把它们说成当前价格。
            3. 每个价格必须带数值、单位和日期；涨跌额与涨跌幅分开表述（如：较上一交易日上涨 140 元/吨，涨幅 1.54%）。
            4. 输出结构清晰的自然语言段落，允许换行与短横线列表。**严禁任何 Markdown 标记**：
               不要用 ** 星号加粗、不要用 * 斜体、不要用 # 标题、不要用 ` 反引号、不要用表格符号。
               想强调就换行、加「」引号或用【】。（系统会强制清除这些符号，写了也不会显示。）
            5. 排行类问题给出前几名即可（如 Top5），不要逐个念完。
            6. 数据有缺失时如实说明“该数据未收录”，不要用空值猜测补全。
            7. 结尾可加一句"数据来源：ChemPrice，数据截至<最新日期>"（日期取自数据包）。
            """;
}
