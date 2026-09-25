package com.datamarket.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.datamarket.common.Result;
import com.datamarket.entity.SysUser;
import com.datamarket.security.PermissionHelper;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 定时服务监控（仅 ADMIN）
 *
 * 说明：本页回答「定时的服务有没有起来」——即 cron 任务是否按点执行、上次跑成功还是失败。
 * 数据来源全部是服务器真实状态，不做任何模拟：
 *   1) crontab -l            —— 任务清单（频率表达式）
 *   2) 日志文件 mtime/大小     —— 上次实际运行时间（cron 每次执行都会写日志）
 *   3) systemd is-active     —— 常驻服务是否存活
 *   4) 日志尾部关键字          —— 上次跑失败还是成功
 */
@RestController
@RequestMapping("/api/admin/scheduler")
@RequiredArgsConstructor
public class SchedulerMonitorController {

    private final JdbcTemplate jdbcTemplate;
    private final PermissionHelper permissionHelper;

    /** is_trading_day.py 结果缓存（起进程开销大，5 分钟有效） */
    private volatile Boolean tradingCache = null;
    private volatile long tradingCacheAt = 0L;

    private static final String HOME = System.getProperty("user.home", "/home/ubuntu");
    private static final String BASE = HOME + "/chemprice";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一个被监控的 cron 任务 */
    private static class TaskDef {
        final String id;
        final String name;
        final String group;
        final String cron;
        final String desc;
        /** 期望运行间隔（分钟），用于判断「是否该跑没跑」 */
        final int everyMin;
        /** 只在交易日运行 */
        final boolean tradingOnly;
        /** 只在特定小时段运行（-1 表示不限），用于行情类任务的窗口判断 */
        final int windowFrom;
        final int windowTo;
        /** 日志文件（相对 ~/chemprice） */
        final String[] logs;

        TaskDef(String id, String name, String group, String cron, String desc,
                int everyMin, boolean tradingOnly, int windowFrom, int windowTo, String... logs) {
            this.id = id; this.name = name; this.group = group; this.cron = cron; this.desc = desc;
            this.everyMin = everyMin; this.tradingOnly = tradingOnly;
            this.windowFrom = windowFrom; this.windowTo = windowTo; this.logs = logs;
        }
    }

    private static final List<TaskDef> TASKS = List.of(
        new TaskDef("crawler", "行情爬虫", "数据采集", "0,30 7-11,13-17 * * *",
            "每 30 分钟抓取全部品种行情，交易日生效", 30, true, 7, 17,
            "scan/daily.log"),
        new TaskDef("favorites", "关注品种抓取", "数据采集", "20,50 7-11,13-17 * * *",
            "每 30 分钟抓取被关注品种（错峰 20 分钟，与爬虫共用 daily.log）", 30, true, 7, 17,
            "scan/daily.log"),
        new TaskDef("refresh_stats", "统计刷新", "数据采集", "5,35 * * * *",
            "采集完成后重算品种统计（有回补任务运行时才跑）", 30, false, -1, -1,
            "scan/refresh.log"),
        new TaskDef("variety_stat", "品种分布汇总", "数据采集", "30 6 * * *",
            "每日 06:30 重算「标点地图」产品维度汇总表", 1440, false, -1, -1,
            "scan/variety_stat.log"),
        new TaskDef("fx_update", "汇率更新", "数据采集", "30 9 * * *",
            "每日 09:30 拉取最新汇率，交易日生效", 1440, true, -1, -1,
            "scan/fx_daily.log"),
        new TaskDef("push_digest", "邮件行情推送", "消息推送", "*/10 * * * *",
            "每 10 分钟检查并发送用户订阅的行情邮件", 10, true, -1, -1,
            "scan/push_digest.log"),
        new TaskDef("demand_notify", "求购邮件通知", "消息推送", "*/5 * * * *",
            "每 5 分钟扫描新求购并通知已认证企业", 5, false, -1, -1,
            "scan/demand_notify.log"),
        new TaskDef("push_task_gc", "推送任务清理", "系统维护", "*/5 * * * *",
            "每 5 分钟清理过期/孤儿推送任务", 5, false, -1, -1,
            "scan/push_gc.log"),
        new TaskDef("expire_supplier", "企业认证过期", "系统维护", "10 1 * * *",
            "每日 01:10 处理到期的企业认证", 1440, false, -1, -1,
            "scan/expire.log"),
        new TaskDef("wechat_morning", "公众号早报", "公众号", "0 8 * * *",
            "每日 08:00 生成早报草稿（交易日）", 1440, true, -1, -1,
            "wechat/out/cron_morning.log"),
        new TaskDef("wechat_today", "公众号今日行情", "公众号", "0 16 * * *",
            "每日 16:00 生成今日行情草稿（交易日）", 1440, true, -1, -1,
            "wechat/out/cron_today.log")
    );

    /**
     * 常驻服务探测方式说明：
     *   - 后端走 start_app.sh 启动（不是 systemd 单元）→ 用健康端口探测
     *   - 注：QQ 相关服务（NapCat / QQ AI 客服 / QQ 推送）已于 2026-09-24 随账号被封下线，不再监控
     *   - 其余走 systemd is-active
     */
    private static final String[][] SERVICES = {
        // name, label, logRel, sysdUnit, tcpPort
        {"chemprice-app", "后端主服务 (Spring Boot)", "app.log", null, "9000"},
        {"chemprice-mcp", "MCP 开放接口服务", null, "chemprice-mcp", "8765"},
        {"nginx", "Nginx 反向代理", null, "nginx", null},
        {"mysql", "MySQL 数据库", null, "mysql", null},
    };

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null || !"ADMIN".equals(u.getRole())) {
            return Result.error(403, "仅管理员可查看");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverTime", LocalDateTime.now().format(FMT));
        boolean trading = tradingDayFlag();
        out.put("isTradingDay", trading);
        out.put("services", servicesStatus());
        out.put("tasks", tasksStatus());

        // 汇总统计
        List<Map<String, Object>> ts = (List<Map<String, Object>>) out.get("tasks");
        // info = 尚无日志（未到点），不算异常；warn / error 才算需关注
        long abnormal = ts.stream()
            .filter(t -> "warn".equals(t.get("level")) || "error".equals(t.get("level")))
            .count();
        out.put("summary", Map.of(
            "total", ts.size(),
            "abnormal", abnormal,
            "normal", ts.size() - abnormal
        ));
        return Result.ok(out);
    }

    /** 判断今天是否交易日：直接问 is_trading_day.py（0=交易日），结果缓存 5 分钟 */
    private boolean tradingDayFlag() {
        long now = System.currentTimeMillis();
        Boolean c = tradingCache;
        if (c != null && now - tradingCacheAt < 5 * 60 * 1000L) {
            return c;
        }
        boolean v = queryTradingDay();
        tradingCache = v;
        tradingCacheAt = now;
        return v;
    }

    private boolean queryTradingDay() {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "is_trading_day.py");
            pb.directory(new File(BASE));
            Process p = pb.start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroy(); return false; }
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return "0".equals(s);
        } catch (Exception e) {
            return false;
        }
    }

    private List<Map<String, Object>> servicesStatus() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String[] svc : SERVICES) {
            String name = svc[0];
            String unit = svc[3];
            String port = svc[4];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", svc[1]);

            boolean active = false;
            String state;

            if (port != null) {
                // 端口探测：最可靠，且不需要 sudo
                active = portOpen(Integer.parseInt(port));
                state = active ? "active" : "port-closed";
            } else {
                String st = runCmd(new String[]{"systemctl", "is-active", unit});
                active = "active".equals(st);
                state = st.isEmpty() ? "unknown" : st;
            }
            m.put("active", active);
            m.put("state", state);
            if (port != null) m.put("port", Integer.parseInt(port));

            // 日志最后更新时间（有日志的服务）
            if (svc[2] != null) {
                File f = new File(BASE, svc[2]);
                if (f.exists()) {
                    m.put("lastLogAt", FMT.format(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(f.lastModified()), ZoneId.systemDefault())));
                    m.put("lastLogAgoSec", (System.currentTimeMillis() - f.lastModified()) / 1000);
                }
            }
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> tasksStatus() {
        List<Map<String, Object>> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        // 只问一次日历，循环内复用（起进程开销大）
        boolean todayTrading = tradingDayFlag();

        for (TaskDef t : TASKS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("name", t.name);
            m.put("group", t.group);
            m.put("cron", t.cron);
            m.put("desc", t.desc);
            m.put("everyMin", t.everyMin);
            m.put("tradingOnly", t.tradingOnly);

            // 取最新的那个日志
            File newest = null;
            for (String rel : t.logs) {
                File f = new File(BASE, rel);
                if (f.exists() && (newest == null || f.lastModified() > newest.lastModified())) newest = f;
            }

            if (newest == null) {
                m.put("lastRunAt", null);
                m.put("lastRunAgoSec", null);
                m.put("logSize", 0);
                // 日志尚未生成：任务可能刚挂上 cron 还没到点。用 info 提示，不当异常。
                m.put("level", "info");
                m.put("reason", "尚无运行日志（任务可能刚配置，或还未到首次执行时间）");
                m.put("tail", "");
                m.put("logFile", BASE + "/" + t.logs[0]);
                list.add(m);
                continue;
            }

            long agoMs = System.currentTimeMillis() - newest.lastModified();
            long agoSec = agoMs / 1000;
            m.put("lastRunAt", FMT.format(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(newest.lastModified()), ZoneId.systemDefault())));
            m.put("lastRunAgoSec", agoSec);
            m.put("logSize", newest.length());
            m.put("logFile", BASE + "/" + t.logs[0]);

            String tail = tailFile(newest, 40);
            m.put("tail", tail);

            // ---- 判定等级 ----
            String level = "ok";
            String reason = "";

            if (t.everyMin <= 1) {
                // 每分钟任务：超过 5 分钟没动静就是有问题
                if (agoSec > 300) { level = "error"; reason = "已 " + human(agoSec) + " 未运行"; }
                else if (agoSec > 150) { level = "warn"; reason = "已有 " + human(agoSec) + " 未运行"; }
            } else if (t.everyMin <= 10) {
                if (agoSec > t.everyMin * 60 * 3) { level = "error"; reason = "已 " + human(agoSec) + " 未运行"; }
                else if (agoSec > t.everyMin * 60 * 2) { level = "warn"; reason = "已有 " + human(agoSec) + " 未运行"; }
            } else if (t.everyMin <= 60) {
                // 行情类：只在 7-17 点窗口内检查超时；窗口外不算异常
                int h = now.getHour();
                boolean inWindow = (t.windowFrom < 0) || (h >= t.windowFrom && h <= t.windowTo);
                if (inWindow && agoSec > t.everyMin * 60 * 4) {
                    level = "error"; reason = "已 " + human(agoSec) + " 未运行";
                } else if (inWindow && agoSec > t.everyMin * 60 * 2) {
                    level = "warn"; reason = "已有 " + human(agoSec) + " 未运行";
                }
            } else {
                // 每日任务：超过 26 小时算超期
                if (agoSec > 26 * 3600) { level = "error"; reason = "已 " + human(agoSec) + " 未运行"; }
                else if (agoSec > 25 * 3600) { level = "warn"; reason = "接近超期，已 " + human(agoSec); }
            }

            // 日志尾部出现错误关键字 → 标记失败（优先于时间判定）
            String errHit = scanError(tail);
            if (errHit != null && level.equals("ok")) {
                level = "warn";
                reason = "日志出现异常：" + errHit;
            } else if (errHit != null && level.equals("warn")) {
                level = "error";
                reason = "日志出现异常：" + errHit;
            }

            // ⭐ 休市日豁免：tradingOnly 任务在休市日的 cron 会执行但脚本直接退出，
            // 日志不更新 → 不能算超时异常，降级为 info 并说明原因。
            if (!todayTrading && t.tradingOnly
                    && ("warn".equals(level) || "error".equals(level))) {
                level = "info";
                reason = "今日休市，该任务不运行（属正常）；上次运行：" +
                    (m.get("lastRunAt") == null ? "无记录" : m.get("lastRunAt"));
            }

            // 交易日限制说明（仍为 warn 时补充口径）
            if (level.equals("warn") && t.tradingOnly && todayTrading) {
                reason = reason + "（该任务仅交易日运行）";
            }

            m.put("level", level);
            m.put("reason", reason.isEmpty() ? "运行正常" : reason);
            list.add(m);
        }
        return list;
    }

    /** 在日志尾部找错误关键字，返回命中的那一行（截断） */
    private String scanError(String tail) {
        if (tail == null || tail.isEmpty()) return null;
        String[] keys = {"Traceback (most recent call last)", "ERROR", "Error:", "Exception",
                         "FAILED", "失败", "异常", "Connection refused", "timeout"};
        String[] lines = tail.split("\n");
        // 只看最近 10 行，避免老错误一直报
        for (int i = Math.max(0, lines.length - 10); i < lines.length; i++) {
            String l = lines[i];
            for (String k : keys) {
                if (l.contains(k)) {
                    String s = l.trim();
                    return s.length() > 90 ? s.substring(0, 90) + "…" : s;
                }
            }
        }
        return null;
    }

    /** 探测本机端口是否在监听（用于判断服务存活，免 sudo 免 systemd） */
    private boolean portOpen(int port) {
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String runCmd(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();
            if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroy(); return ""; }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 读文件末尾若干行（大文件只读最后 32KB，避免内存问题） */
    static String tailFile(File f, int lines) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long read = Math.min(len, 32768);
            raf.seek(len - read);
            byte[] buf = new byte[(int) read];
            raf.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8);
            // 截断可能产生半个字符/半行，丢弃第一行
            if (read < len) {
                int idx = s.indexOf('\n');
                if (idx >= 0) s = s.substring(idx + 1);
            }
            String[] all = s.split("\n");
            int from = Math.max(0, all.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.length; i++) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(all[i]);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String human(long sec) {
        if (sec < 60) return sec + " 秒";
        if (sec < 3600) return (sec / 60) + " 分钟";
        if (sec < 86400) return String.format("%.1f 小时", sec / 3600.0);
        return String.format("%.1f 天", sec / 86400.0);
    }

    /** 查看某任务日志尾部（供页面「查看日志」用） */
    @GetMapping("/log")
    public Result<Map<String, Object>> log(@RequestParam String id,
                                          @RequestParam(defaultValue = "200") int lines) {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null || !"ADMIN".equals(u.getRole())) {
            return Result.error(403, "仅管理员可查看");
        }
        TaskDef hit = TASKS.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
        if (hit == null) return Result.error(400, "未知任务：" + id);

        File newest = null;
        for (String rel : hit.logs) {
            File f = new File(BASE, rel);
            if (f.exists() && (newest == null || f.lastModified() > newest.lastModified())) newest = f;
        }
        if (newest == null) return Result.error(400, "日志文件不存在");

        int n = Math.max(10, Math.min(lines, 2000));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", newest.getAbsolutePath());
        m.put("size", newest.length());
        m.put("lastRunAt", FMT.format(LocalDateTime.ofInstant(
            Instant.ofEpochMilli(newest.lastModified()), ZoneId.systemDefault())));
        m.put("content", tailFile(newest, n));
        return Result.ok(m);
    }

    /** 手动触发（白名单，仅限幂等/安全的脚本） */
    @GetMapping("/run")
    public Result<String> run(@RequestParam String id) {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null || !"ADMIN".equals(u.getRole())) {
            return Result.error(403, "仅管理员可操作");
        }
        // 只允许手动触发这几个安全脚本，避免误跑爬虫/群发
        Map<String, String> allow = Map.of(
            "refresh_stats", "python3 refresh_stats.py",
            "variety_stat", "bash refresh_variety_stat.sh",
            "push_task_gc", "python3 push_task_gc.py",
            "demand_notify", "python3 demand_notify.py"
        );
        String cmd = allow.get(id);
        if (cmd == null) return Result.error(400, "该任务不支持手动触发：" + id);

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", cmd + " >> scan/manual_run.log 2>&1");
            pb.directory(new File(BASE));
            pb.start();
            return Result.ok("已触发：" + cmd + "（结果见 scan/manual_run.log）");
        } catch (Exception e) {
            return Result.error(500, "触发失败：" + e.getMessage());
        }
    }
}
