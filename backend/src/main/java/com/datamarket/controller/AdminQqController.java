package com.datamarket.controller;

import com.datamarket.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员：QQ 群推送目标管理（/api/admin/qq-notify/**）。
 * 权限由 SecurityConfig 的 /api/admin/** hasRole("ADMIN") 保证，这里不做重复校验。
 *
 * 四张表：
 *   qq_bot_group       —— 机器人当前所在群（服务器 cron 脚本 qq_notify.py 自动同步，供页面选择）
 *   qq_notify_group    —— 已开启「新求购推送」的群（本页开关；字段 enabled 决定是否推送）
 *   qq_broadcast_log   —— 「一键宣发」发送记录（手工文案群发，按群逐条留痕）
 *
 * 服务器端 qq_notify.py 每 5 分钟读一次 qq_notify_group，因此这里的开关**无需重启任何服务**，
 * 最多 5 分钟内生效。
 *
 * 「一键宣发」与求购推送是两条独立链路：
 *   - 求购推送：走服务器 qq_notify.py（有去重日志、每 5 分钟一轮）→ /push-all
 *   - 一键宣发：管理员自己写文案，本 Controller 直接调 NapCat HTTP 接口逐个群发送 → /broadcast
 */
@RestController
@RequestMapping("/api/admin/qq-notify")
public class AdminQqController {

    private final JdbcTemplate jdbc;

    /** 服务器端推送脚本所在目录（与 cron 用的是同一份 qq_notify.py） */
    private static final String SYSTEM_BASE = "/home/ubuntu/chemprice";

    /** NapCat（OneBot 实现）本机接口，只监听 127.0.0.1 */
    private static final String NAPCAT_BASE = "http://127.0.0.1:3000";
    private static final String NAPCAT_TOKEN_FILE = "/home/ubuntu/napcat/.token";

    /** 宣发文案长度上限（QQ 群文本消息实际可承载，超长会被截断或发送失败） */
    private static final int BROADCAST_MAX_LEN = 1500;
    /** 单次宣发最多发送的群数 */
    private static final int BROADCAST_MAX_GROUPS = 50;
    /** 每个群之间的间隔（毫秒），避免短时间大量相同内容触发风控 */
    private static final long BROADCAST_GAP_MS = 800;

    /**
     * 宣发末尾附带的站点二维码图片。
     * 优先用 **web 根** 的那份（`/site-qr.png`），这样管理页可以 <img> 直接预览同一张图，
     * 不必维护两份；读不到再回退到脚本侧 `assets/site-qr.png`（qq_notify.py 用的那份）。
     */
    private static final String[] QR_PATHS = {
            "/var/www/chemprice/site-qr.png",
            "/home/ubuntu/chemprice/assets/site-qr.png"
    };
    private static final String QR_CAPTION = "扫码打开 ChemPrice · 长按识别二维码";
    private static final String QR_WEB_PATH = "/site-qr.png";

    private volatile String qrB64 = null;
    private volatile boolean qrTried = false;

    private final ObjectMapper json = new ObjectMapper();

    public AdminQqController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 机器人所在群 + 是否已开启推送 */
    @GetMapping("/groups")
    public Result<Map<String, Object>> groups() {
        ensureTables();

        List<Map<String, Object>> bot = jdbc.queryForList(
                "SELECT b.group_id   AS groupId, " +
                "       b.group_name AS groupName, " +
                "       b.member_count AS memberCount, " +
                "       DATE_FORMAT(b.updated_at, '%Y-%m-%d %H:%i:%s') AS updatedAt, " +
                "       CASE WHEN g.enabled = 1 THEN 1 ELSE 0 END      AS enabled, " +
                "       COALESCE(g.note, '')                            AS note, " +
                "       1                                               AS inBot " +
                "  FROM qq_bot_group b " +
                "  LEFT JOIN qq_notify_group g ON g.group_id = b.group_id " +
                " ORDER BY (g.enabled = 1) DESC, b.member_count DESC, b.group_id");

        // 配了推送、但机器人当前不在该群（可能已退群/被踢）——单独列出提醒
        List<Map<String, Object>> orphan = jdbc.queryForList(
                "SELECT g.group_id AS groupId, '' AS groupName, 0 AS memberCount, " +
                "       DATE_FORMAT(g.created_at, '%Y-%m-%d %H:%i:%s') AS updatedAt, " +
                "       CASE WHEN g.enabled = 1 THEN 1 ELSE 0 END      AS enabled, " +
                "       COALESCE(g.note, '')                            AS note, " +
                "       0                                               AS inBot " +
                "  FROM qq_notify_group g " +
                " WHERE NOT EXISTS (SELECT 1 FROM qq_bot_group b WHERE b.group_id = g.group_id) " +
                " ORDER BY g.group_id");

        List<Map<String, Object>> all = new ArrayList<>(bot);
        all.addAll(orphan);

        long pushCount = all.stream()
                .filter(r -> num(r.get("enabled")) == 1)
                .count();

        String syncedAt = jdbc.queryForObject(
                "SELECT DATE_FORMAT(MAX(updated_at), '%Y-%m-%d %H:%i:%s') FROM qq_bot_group", String.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", all);
        out.put("pushCount", pushCount);
        out.put("botGroupCount", bot.size());
        out.put("syncedAt", syncedAt);
        return Result.ok(out);
    }

    /** 开启 / 关闭某个群的推送（也用于手动添加机器人尚未加入的群号） */
    @PostMapping("/groups")
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        ensureTables();

        Long gid = toLong(body.get("groupId"));
        if (gid == null || gid <= 0) {
            return Result.error("群号不合法");
        }
        Object rawEnabled = body.get("enabled");
        boolean enabled = rawEnabled == null
                || (!"0".equals(String.valueOf(rawEnabled))
                    && !"false".equalsIgnoreCase(String.valueOf(rawEnabled)));

        String note = body.get("note") == null ? "" : String.valueOf(body.get("note")).trim();
        if (note.length() > 64) {
            note = note.substring(0, 64);
        }

        jdbc.update("INSERT INTO qq_notify_group (group_id, enabled, note) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), note = VALUES(note)",
                    gid, enabled ? 1 : 0, note);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupId", gid);
        out.put("enabled", enabled ? 1 : 0);
        return Result.ok(out);
    }

    /** 移除某个群的推送配置（不改变 QQ 群本身，仅停止推送） */
    @DeleteMapping("/groups/{groupId}")
    public Result<Map<String, Object>> remove(@PathVariable Long groupId) {
        ensureTables();
        if (groupId == null || groupId <= 0) {
            return Result.error("群号不合法");
        }
        jdbc.update("DELETE FROM qq_notify_group WHERE group_id = ?", groupId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupId", groupId);
        return Result.ok(out);
    }

    /**
     * 一键推送前的预览：当前在线求购条数 / 标题列表 + 将接收推送的群。
     * 供前端二次确认框展示范围——避免管理员不明就里地往几个真实群群发。
     */
    @GetMapping("/push-all/preview")
    public Result<Map<String, Object>> pushAllPreview() {
        ensureTables();

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM demand_post WHERE type = 'demand' AND status = 'online'", Long.class);

        // 预览最多列 50 条，够展示范围即可
        List<Map<String, Object>> demands = jdbc.queryForList(
                "SELECT id, title, COALESCE(varieties_name, '') AS varietiesName, quantity, " +
                "       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS createdAt " +
                "  FROM demand_post WHERE type = 'demand' AND status = 'online' " +
                " ORDER BY id DESC LIMIT 50");

        List<Map<String, Object>> groups = jdbc.queryForList(
                "SELECT g.group_id AS groupId, COALESCE(b.group_name, '') AS groupName " +
                "  FROM qq_notify_group g " +
                "  LEFT JOIN qq_bot_group b ON b.group_id = g.group_id " +
                " WHERE g.enabled = 1 ORDER BY g.group_id");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("demandCount", total == null ? 0 : total);
        out.put("groupCount", groups.size());
        out.put("demands", demands);
        out.put("groups", groups);
        return Result.ok(out);
    }

    /**
     * 一键推送：把**当前全部在线求购**立即推送到所有已开启的群（忽略逐群去重）。
     * 与 cron 的差异：cron 只推「没推过的」，这里会把已推过的也重发一遍，
     * 用于补发/全量刷新；发送成功后仍写去重日志，因此下一轮 cron 不会重复。
     */
    @PostMapping("/push-all")
    public Result<Map<String, Object>> pushAll() {
        ensureTables();

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM demand_post WHERE type = 'demand' AND status = 'online'", Long.class);
        if (total == null || total == 0) {
            return Result.error("当前没有在线求购，无需推送");
        }
        Long gcount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qq_notify_group WHERE enabled = 1", Long.class);
        if (gcount == null || gcount == 0) {
            return Result.error("还没有任何群开启推送，请先在上方打开开关");
        }

        String script = SYSTEM_BASE + "/qq_notify.py";
        if (!new File(script).exists()) {
            return Result.error("推送脚本不存在：" + script);
        }

        try {
            // --all：忽略去重日志，全量重发（与 cron 共用同一份逻辑与同一个文件锁）
            ProcessBuilder pb = new ProcessBuilder("python3", script, "--all");
            pb.directory(new File(SYSTEM_BASE));
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Result.error("推送超时（超过 120 秒），请到服务器查看 scan/qq_notify.log 确认结果");
            }
            String log = sb.toString();
            if (log.contains("另一个推送实例正在运行")) {
                return Result.error(409, "定时推送正在执行，请等几秒后重试");
            }
            if (p.exitValue() != 0) {
                return Result.error("推送脚本执行失败（退出码 " + p.exitValue() + "）");
            }

            int sent = countOccurrences(log, "已发送 -> 群");
            int failed = countOccurrences(log, "发送失败 -> 群") + countOccurrences(log, "发送异常 -> 群");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("demandCount", total);
            out.put("groupCount", gcount);
            out.put("sentGroups", sent);
            out.put("failedGroups", failed);
            out.put("log", log.length() > 4000 ? log.substring(log.length() - 4000) : log);
            return Result.ok(out);
        } catch (Exception e) {
            return Result.error("推送执行异常：" + e.getMessage());
        }
    }

    // ---------------- 一键宣发（管理员自定义文案群发） ----------------

    /**
     * 宣发目标群：机器人当前所在、且群号有效的群。
     * 与求购推送无关——宣发是独立动作，不加「是否开启推送」的限制，
     * 页面上默认全选，管理员可取消勾选排除个人群。
     */
    @GetMapping("/broadcast/targets")
    public Result<Map<String, Object>> broadcastTargets() {
        ensureTables();

        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT b.group_id AS groupId, " +
                "       b.group_name AS groupName, " +
                "       b.member_count AS memberCount, " +
                "       CASE WHEN g.enabled = 1 THEN 1 ELSE 0 END AS pushEnabled " +
                "  FROM qq_bot_group b " +
                "  LEFT JOIN qq_notify_group g ON g.group_id = b.group_id " +
                " ORDER BY (g.enabled = 1) DESC, b.member_count DESC, b.group_id");

        int members = list.stream().mapToInt(r -> num(r.get("memberCount"))).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", list);
        out.put("groupCount", list.size());
        out.put("memberTotal", members);
        out.put("qrWebPath", QR_WEB_PATH);
        out.put("qrReady", qrBase64() != null);
        out.put("history", recentBroadcasts(8));
        return Result.ok(out);
    }

    /**
     * 一键宣发：把管理员自己写的文案发送到所选群。
     * 请求体：{ text: 文案, groupIds: [群号...]（可空=全部机器人所在群）, dryRun: true 只校验不发送 }
     * 返回：{ batchId, total, sent, failed, results: [{groupId, groupName, ok, detail}] }
     */
    @PostMapping("/broadcast")
    public Result<Map<String, Object>> broadcast(@RequestBody Map<String, Object> body) {
        ensureTables();

        String text = body.get("text") == null ? "" : String.valueOf(body.get("text")).trim();
        if (text.isEmpty()) {
            return Result.error("宣发文案不能为空");
        }
        if (text.length() > BROADCAST_MAX_LEN) {
            return Result.error("宣发文案过长（最多 " + BROADCAST_MAX_LEN + " 字，当前 " + text.length() + " 字）");
        }

        List<Long> targets = new ArrayList<>();
        Object rawIds = body.get("groupIds");
        if (rawIds instanceof List<?> l && !l.isEmpty()) {
            for (Object o : l) {
                Long g = toLong(o);
                if (g != null && g > 0 && !targets.contains(g)) {
                    targets.add(g);
                }
            }
        } else {
            for (Map<String, Object> r : jdbc.queryForList("SELECT group_id FROM qq_bot_group")) {
                Long g = toLong(r.get("group_id"));
                if (g != null && g > 0) {
                    targets.add(g);
                }
            }
        }
        if (targets.isEmpty()) {
            return Result.error("没有可发送的群：机器人当前不在任何群里");
        }
        if (targets.size() > BROADCAST_MAX_GROUPS) {
            return Result.error("一次最多发送 " + BROADCAST_MAX_GROUPS + " 个群（当前选中 " + targets.size() + " 个）");
        }

        // 群名映射（日志与回显用），同时用来校验「机器人确实在这个群里」
        Map<Long, String> nameMap = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT group_id, group_name FROM qq_bot_group")) {
            Long g = toLong(r.get("group_id"));
            if (g != null) {
                nameMap.put(g, String.valueOf(r.get("group_name") == null ? "" : r.get("group_name")));
            }
        }

        // 机器人不在的群直接剔除：发过去必然失败，不如提前告诉管理员
        List<Long> skipped = new ArrayList<>();
        targets.removeIf(g -> {
            if (nameMap.containsKey(g)) {
                return false;
            }
            skipped.add(g);
            return true;
        });
        if (targets.isEmpty()) {
            return Result.error("所选群里机器人都不在，无法发送（已跳过：" + joinIds(skipped) + "）");
        }

        // 末尾是否附带站点二维码图片（不传=附加，管理页默认勾选）
        boolean withQr = !body.containsKey("withQr") || truthy(body.get("withQr"));
        String qr = withQr ? qrBase64() : null;
        boolean qrAttached = qr != null;
        // 勾了二维码但图读不到 → 退回纯文本，并在响应里说明，不让管理员以为发出去了
        String qrError = (withQr && qr == null) ? "二维码图片读取失败，本次仅发送文字" : "";

        boolean dryRun = truthy(body.get("dryRun"));
        if (dryRun) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Long g : targets) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("groupId", g);
                m.put("groupName", nameMap.getOrDefault(g, ""));
                m.put("ok", true);
                m.put("detail", "试运行，未发送");
                results.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dryRun", true);
            out.put("total", targets.size());
            out.put("sent", 0);
            out.put("failed", 0);
            out.put("skipped", skipped);
            out.put("qrAttached", qrAttached);
            out.put("qrError", qrError);
            out.put("qrWebPath", QR_WEB_PATH);
            out.put("textLength", text.length());
            out.put("results", results);
            return Result.ok(out);
        }

        String token;
        try {
            token = Files.readString(Path.of(NAPCAT_TOKEN_FILE), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return Result.error("读取 QQ 机器人凭据失败（" + NAPCAT_TOKEN_FILE + "）：" + e.getMessage());
        }
        if (token.isEmpty()) {
            return Result.error("QQ 机器人凭据为空，请检查 NapCat 是否已登录");
        }

        String batchId = "BC" + System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        int sent = 0, failed = 0;

        for (int i = 0; i < targets.size(); i++) {
            Long g = targets.get(i);
            String gname = nameMap.getOrDefault(g, "");
            boolean ok;
            String detail;
            try {
                String resp = sendGroupMsg(token, g, text, qr);
                Map<?, ?> rm = json.readValue(resp, Map.class);
                ok = "ok".equals(String.valueOf(rm.get("status")));
                detail = ok
                        ? "message_id=" + String.valueOf(rm.get("data") == null ? "" : ((Map<?, ?>) rm.get("data")).get("message_id"))
                        : "retcode=" + rm.get("retcode") + " " + rm.get("message");
            } catch (Exception e) {
                ok = false;
                detail = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            }
            if (ok) {
                sent++;
            } else {
                failed++;
            }
            logBroadcast(batchId, g, gname, ok ? "ok" : "fail", detail, text);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupId", g);
            m.put("groupName", gname);
            m.put("ok", ok);
            m.put("detail", detail);
            results.add(m);

            // 群之间留间隔，降低风控概率（最后一个群不用等）
            if (i < targets.size() - 1) {
                try {
                    Thread.sleep(BROADCAST_GAP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("total", targets.size());
        out.put("sent", sent);
        out.put("failed", failed);
        out.put("skipped", skipped);
        out.put("qrAttached", qrAttached);
        out.put("qrError", qrError);
        out.put("results", results);
        out.put("history", recentBroadcasts(8));
        return Result.ok(out);
    }

    /** 站点二维码 → base64（只读一次并缓存；失败返回 null 表示没有图） */
    private String qrBase64() {
        if (!qrTried) {
            synchronized (this) {
                if (!qrTried) {
                    for (String p : QR_PATHS) {
                        try {
                            byte[] b = Files.readAllBytes(Path.of(p));
                            qrB64 = java.util.Base64.getEncoder().encodeToString(b);
                            break;
                        } catch (Exception ignore) {
                            // 换下一个候选路径
                        }
                    }
                    qrTried = true;
                }
            }
        }
        return qrB64;
    }

    /** 把群号列表拼成 "a、b、c"（错误提示用） */
    private String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** 最近的宣发记录（按批次聚合，取每个批次的首条） */
    @GetMapping("/broadcast/history")
    public Result<List<Map<String, Object>>> broadcastHistory(
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        ensureTables();
        return Result.ok(recentBroadcasts(Math.max(1, Math.min(limit, 50))));
    }

    private List<Map<String, Object>> recentBroadcasts(int limit) {
        return jdbc.queryForList(
                "SELECT batch_id AS batchId, " +
                "       DATE_FORMAT(MIN(created_at), '%Y-%m-%d %H:%i:%s') AS sentAt, " +
                "       COUNT(*) AS groupCount, " +
                "       SUM(CASE WHEN status = 'ok' THEN 1 ELSE 0 END) AS okCount, " +
                "       MIN(substr(COALESCE(text_body, ''), 1, 60)) AS textPreview " +
                "  FROM qq_broadcast_log " +
                " GROUP BY batch_id ORDER BY MIN(created_at) DESC LIMIT " + limit);
    }

    private void logBroadcast(String batchId, Long gid, String gname, String status,
                              String detail, String text) {
        try {
            jdbc.update("INSERT INTO qq_broadcast_log " +
                        " (batch_id, group_id, group_name, status, detail, text_len, text_body) " +
                        " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    batchId, gid, gname == null ? "" : gname, status,
                    detail == null ? "" : (detail.length() > 250 ? detail.substring(0, 250) : detail),
                    text.length(),
                    text.length() > 500 ? text.substring(0, 500) : text);
        } catch (Exception ignore) {
            // 记录失败不影响发送结果
        }
    }

    /**
     * 调 NapCat 的 send_group_msg（本机 127.0.0.1:3000，Bearer token）。
     * qrB64 非空时改用**消息段数组**：[文字, 图片]；二维码用 `base64://` 传
     * （NapCat 在容器里，`file://` 路径解析不到宿主机的图，必须内联 base64）。
     */
    private String sendGroupMsg(String token, long groupId, String text, String qrB64) throws Exception {
        Object message;
        if (qrB64 == null || qrB64.isEmpty()) {
            message = text;
        } else {
            List<Map<String, Object>> segs = new ArrayList<>();
            segs.add(Map.of("type", "text", "data", Map.of("text", text + "\n\n" + QR_CAPTION)));
            segs.add(Map.of("type", "image", "data", Map.of("file", "base64://" + qrB64)));
            message = segs;
        }
        String payload = json.writeValueAsString(Map.of("group_id", groupId, "message", message));
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(NAPCAT_BASE + "/send_group_msg"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(40))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        java.net.http.HttpResponse<String> resp = client.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body();
    }

    /** 表不存在时兜底建表（正常由服务器 qq_notify.py / db_setup 建好） */
    private void ensureTables() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS qq_bot_group (" +
                         " group_id BIGINT NOT NULL PRIMARY KEY," +
                         " group_name VARCHAR(128) NOT NULL DEFAULT ''," +
                         " member_count INT NOT NULL DEFAULT 0," +
                         " updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                         ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbc.execute("CREATE TABLE IF NOT EXISTS qq_notify_group (" +
                         " group_id BIGINT NOT NULL PRIMARY KEY," +
                         " enabled TINYINT NOT NULL DEFAULT 1," +
                         " note VARCHAR(64) NOT NULL DEFAULT ''," +
                         " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                         ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbc.execute("CREATE TABLE IF NOT EXISTS qq_broadcast_log (" +
                         " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                         " batch_id VARCHAR(40) NOT NULL DEFAULT ''," +
                         " group_id BIGINT NOT NULL DEFAULT 0," +
                         " group_name VARCHAR(128) NOT NULL DEFAULT ''," +
                         " status VARCHAR(16) NOT NULL DEFAULT ''," +
                         " detail VARCHAR(255) NOT NULL DEFAULT ''," +
                         " text_len INT NOT NULL DEFAULT 0," +
                         " text_body TEXT NULL," +
                         " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                         " KEY idx_batch (batch_id)," +
                         " KEY idx_created (created_at)" +
                         ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception ignore) {
            // 建表失败不阻断查询（表已存在时也可能抛权限/语法告警）
        }
    }

    private int num(Object o) {
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean truthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(o).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /** 统计子串出现次数（解析推送脚本输出用） */
    private static int countOccurrences(String s, String sub) {
        if (s == null || s.isEmpty() || sub == null || sub.isEmpty()) {
            return 0;
        }
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }
}
