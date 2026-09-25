package com.datamarket.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

/**
 * 邮件发送中心。
 *
 * 约定：所有发送方法都 @Async 且内部吞异常——邮件只是通知手段，
 * 失败绝不能影响认证提交/审核、验证码等主业务流程。失败只记 error 日志。
 */
@Service
@Slf4j
public class EmailService {

    private static final String BRAND = "ChemPrice 化工价格平台";

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** 通用 HTML 邮件（异步、失败只记日志） */
    @Async
    public void sendHtml(String toEmail, String subject, String html) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("跳过发信（收件人为空）: {}", subject);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, BRAND);
            helper.setTo(toEmail.trim());
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("邮件已发送: 「{}」-> {}", subject, toEmail);
        } catch (Exception e) {
            log.error("邮件发送失败: 「{}」-> {}: {}", subject, toEmail, e.getMessage());
        }
    }

    /** 验证码邮件（保留原方法签名，供 AuthService 调用） */
    @Async
    public void sendVerificationCode(String toEmail, String code, String purpose) {
        String body = """
            <p style="margin:0 0 18px;color:#4b5563;font-size:14px;line-height:1.7;">你正在%s，验证码如下：</p>
            <div style="text-align:center;padding:20px;background:#f0fdfa;border:1px solid #ccfbf1;border-radius:12px;margin-bottom:18px;">
              <span style="font-size:32px;font-weight:800;letter-spacing:8px;color:#0f766e;font-family:Consolas,'JetBrains Mono',monospace;">%s</span>
            </div>
            <p style="margin:0;color:#9ca3af;font-size:12px;">验证码 5 分钟内有效，请勿泄露给他人。</p>
            """.formatted(esc(purpose), esc(code));
        sendHtml(toEmail, "ChemPrice - 验证码", wrap("ChemPrice 验证码", "#0f766e", body));
    }

    /* ==================== 邮件 HTML 模板工具（供各业务复用） ==================== */

    /**
     * HTML 转义。邮件正文会插入用户填写的内容（公司名、联系人、审核备注等），
     * 不转义可能注入链接或撑破排版，且这封邮件会发给其他人（管理员）。
     */
    public static String esc(Object o) {
        if (o == null) return "";
        return String.valueOf(o)
                .replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 统一外壳：品牌头 + 标题 + 正文 + 页脚。accent 为主色，用于标题与图标。 */
    public static String wrap(String title, String accent, String bodyHtml) {
        return """
            <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;background:#f4f6f8;padding:24px 12px;">
              <div style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:14px;overflow:hidden;border:1px solid #e5e7eb;">
                <div style="background:#15414E;padding:18px 24px;">
                  <span style="display:inline-block;width:32px;height:32px;border-radius:9px;background:#5ECBB0;color:#0f2b33;font-weight:800;font-size:13px;line-height:32px;text-align:center;vertical-align:middle;">CM</span>
                  <span style="color:#e8f7f3;font-size:14px;font-weight:600;margin-left:10px;vertical-align:middle;">ChemPrice 化工价格平台</span>
                </div>
                <div style="padding:22px 24px;">
                  <h2 style="margin:0 0 14px;font-size:17px;color:%s;">%s</h2>
                  %s
                </div>
                <div style="padding:13px 24px;background:#fafafa;border-top:1px solid #f0f0f0;">
                  <p style="margin:0;font-size:11px;color:#9ca3af;line-height:1.7;">
                    本邮件由系统自动发送，请勿直接回复。<br>数据来源：ChemPrice 化工价格平台
                  </p>
                </div>
              </div>
            </div>
            """.formatted(accent, esc(title), bodyHtml);
    }

    /** 键值行（放进 table 里） */
    public static String row(String key, Object val) {
        String v = (val == null || String.valueOf(val).isBlank()) ? "—" : esc(val);
        return "<tr><td style=\"padding:5px 0;color:#6b7280;font-size:13px;width:88px;vertical-align:top;\">"
                + esc(key) + "</td><td style=\"padding:5px 0;color:#111827;font-size:13px;font-weight:600;\">"
                + v + "</td></tr>";
    }

    /** 主按钮 */
    public static String btn(String text, String href) {
        return "<div style=\"margin:18px 0 4px;\"><a href=\"" + esc(href)
                + "\" style=\"display:inline-block;padding:10px 20px;background:#0f766e;color:#ffffff;"
                + "font-size:13px;font-weight:600;text-decoration:none;border-radius:8px;\">"
                + esc(text) + "</a></div>";
    }

    /** 状态色块（通过=绿 / 未通过=红 / 待处理=橙） */
    public static String badge(String text, String tone) {
        String bg, bd, fg;
        if ("ok".equals(tone)) {
            bg = "#f0fdfa"; bd = "#ccfbf1"; fg = "#0f766e";
        } else if ("bad".equals(tone)) {
            bg = "#fef2f2"; bd = "#fecaca"; fg = "#b91c1c";
        } else {
            bg = "#fffbeb"; bd = "#fde68a"; fg = "#b45309";
        }
        return "<div style=\"padding:11px 14px;background:" + bg + ";border:1px solid " + bd
                + ";border-radius:10px;margin-bottom:16px;\"><span style=\"color:" + fg
                + ";font-size:14px;font-weight:700;\">" + esc(text) + "</span></div>";
    }
}
