package com.datamarket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证通知中心（实名认证 / 企业认证）。
 *
 * 两类通知：
 *  1) <b>新申请</b> → 站内信 + 邮件，通知**全部管理员**（角色 role=ADMIN，邮箱取其账号邮箱）；
 *  2) <b>审核结果</b> → 邮件通知**申请人**（站内信由各 Controller 原有逻辑写入，此处不重复）。
 *
 * 所有对外方法都整体吞异常并只记日志——通知只是辅助，绝不能影响认证业务本身的成败。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerifyNotifyService {

    private final JdbcTemplate jdbcTemplate;
    private final EmailService emailService;

    /** 站点基址（邮件里的跳转链接用），可在 application.yml 用 chemprice.site.url 覆盖 */
    @Value("${chemprice.site.url:http://82.156.8.214}")
    private String siteUrl;

    /* ==================== 对外：两个业务入口 ==================== */

    /**
     * 有新的认证申请 → 站内信 + 邮件通知全部管理员。
     *
     * @param kind   认证类型文案，如「企业认证」「实名认证」
     * @param fields 要展示给管理员的字段（有序），键为中文标签
     */
    public void onNewApply(String kind, Map<String, String> fields) {
        try {
            String title = "新" + kind + "申请";

            // --- 站内信（全部管理员） ---
            StringBuilder c = new StringBuilder("收到一条新的" + kind + "申请，请及时审核。\n");
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    c.append("· ").append(e.getKey()).append("：").append(e.getValue()).append('\n');
                }
            }
            int msgOk = 0;
            for (Long adminId : adminIds()) {
                try {
                    jdbcTemplate.update(
                            "INSERT INTO site_message (user_id, title, content, type) VALUES (?,?,?,?)",
                            adminId, title, c.toString().trim(), "verify_apply");
                    msgOk++;
                } catch (Exception e) {
                    log.warn("站内信写入失败 adminId={}: {}", adminId, e.getMessage());
                }
            }

            // --- 邮件（全部管理员邮箱） ---
            String html = EmailService.wrap(title, "#b45309",
                    EmailService.badge("⏳ 待审核", "warn")
                  + "<p style=\"margin:0 0 14px;color:#4b5563;font-size:13px;line-height:1.8;\">"
                  + "平台收到一条新的<b>" + EmailService.esc(kind) + "</b>申请，请登录后台及时审核。</p>"
                  + tableOf(fields)
                  + EmailService.btn("前往后台审核", siteUrl + "/supplier-audit"));

            String first = fields.values().stream()
                    .filter(v -> v != null && !v.isBlank()).findFirst().orElse("").trim();
            String subject = "【ChemPrice】" + title + (first.isEmpty() ? "" : "：" + clip(first, 30));

            int mailOk = 0;
            for (String mail : adminMails()) {
                if (mail == null || mail.isBlank()) continue;
                emailService.sendHtml(mail, subject, html);
                mailOk++;
            }
            log.info("已通知管理员：{}（站内信 {} 人 / 邮件 {} 人）", title, msgOk, mailOk);
        } catch (Exception e) {
            log.error("通知管理员失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 审核结果 → 邮件通知申请人。
     * 站内信由各审核入口原有逻辑负责，此处只补邮件，避免重复。
     *
     * @param kind     认证类型文案
     * @param approved 通过 / 未通过
     * @param fields   要展示的补充字段（如公司名称、审核说明）
     */
    public void onReviewed(Long userId, String kind, boolean approved, Map<String, String> fields) {
        try {
            String mail = emailOf(userId);
            if (mail == null || mail.isBlank()) {
                log.info("用户 {} 无可用邮箱，跳过认证结果邮件", userId);
                return;
            }
            String title = kind + (approved ? "已通过" : "未通过");

            String body;
            if (approved) {
                String next = "实名认证".equals(kind)
                        ? "，现在可以发布求购与供应信息了。"
                        : "，现在可以发布供应信息，并可在「供需广场」接收新求购的邮件通知。";
                body = EmailService.badge("✓ 认证已通过", "ok")
                     + "<p style=\"margin:0 0 14px;color:#4b5563;font-size:13px;line-height:1.8;\">"
                     + "恭喜！你的" + EmailService.esc(kind) + "已审核通过" + next + "</p>"
                     + tableOf(fields)
                     + EmailService.btn("查看认证状态", siteUrl + "/supplier-verify");
            } else {
                body = EmailService.badge("✕ 认证未通过", "bad")
                     + "<p style=\"margin:0 0 14px;color:#4b5563;font-size:13px;line-height:1.8;\">"
                     + "很抱歉，你的" + EmailService.esc(kind) + "申请未通过审核。"
                     + "你可以按下方说明补充材料后重新提交，如有疑问请联系平台管理员。</p>"
                     + tableOf(fields)
                     + EmailService.btn("重新提交认证", siteUrl + "/supplier-verify");
            }

            emailService.sendHtml(mail, "【ChemPrice】" + title,
                    EmailService.wrap(title, approved ? "#0f766e" : "#b91c1c", body));
        } catch (Exception e) {
            log.error("发送认证结果邮件失败 userId={}: {}", userId, e.getMessage(), e);
        }
    }

    /* ==================== 内部工具 ==================== */

    private List<Long> adminIds() {
        try {
            return jdbcTemplate.queryForList("SELECT id FROM sys_user WHERE role = 'ADMIN'", Long.class);
        } catch (Exception e) {
            log.warn("查询管理员列表失败: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** 管理员邮箱：优先账号 email，回退常用联系邮箱 */
    private List<String> adminMails() {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT COALESCE(NULLIF(TRIM(email),''), NULLIF(TRIM(default_contact_email),'')) AS m " +
                    "FROM sys_user WHERE role = 'ADMIN'", String.class);
        } catch (Exception e) {
            log.warn("查询管理员邮箱失败: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** 申请人邮箱：与「求购通知」口径一致 —— 通知邮箱 > 常用联系邮箱 > 注册邮箱 */
    private String emailOf(Long userId) {
        try {
            List<String> r = jdbcTemplate.queryForList(
                    "SELECT COALESCE(NULLIF(TRIM(demand_notify_email),''), NULLIF(TRIM(default_contact_email),''), "
                  + "NULLIF(TRIM(email),'')) FROM sys_user WHERE id = ?", String.class, userId);
            return r.isEmpty() ? null : r.get(0);
        } catch (Exception e) {
            log.warn("查询用户邮箱失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private static String tableOf(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<table style=\"width:100%;border-collapse:collapse;\">");
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append(EmailService.row(e.getKey(), e.getValue()));
        }
        return sb.append("</table>").toString();
    }

    /** 截断（主题行防过长） */
    private static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    /** 便捷构造有序字段表 */
    public static Map<String, String> fields(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
