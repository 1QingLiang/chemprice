package com.datamarket.controller;

import com.datamarket.service.wechat.WechatMpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 微信公众号服务器回调入口（订阅号，被动回复模式）。
 * <p>
 * GET  /api/wechat/mp —— 微信校验服务器地址（SHA1(Token,timestamp,nonce) 比对，回 echostr）
 * POST /api/wechat/mp —— 收到用户消息，**5 秒内**回 XML 文本消息
 * <p>
 * 注意：微信要求 URL 走 80/443 端口；本站为 http://82.156.8.214/api/wechat/mp（nginx 80 → 后端 9000）。
 * 该路径在 SecurityConfig 中放行（微信服务器不带 JWT）。
 */
@RestController
@RequestMapping("/api/wechat/mp")
@RequiredArgsConstructor
@Slf4j
public class WechatMpController {

    private final WechatMpService wechatMpService;

    /** 与公众号后台「服务器配置」里的 Token 保持一致；生产用环境变量注入 */
    @Value("${chemprice.wechat.mp.token:}")
    private String token;

    @GetMapping(produces = "text/plain;charset=UTF-8")
    public String verify(@RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr) {
        if (token == null || token.isBlank()) {
            log.warn("公众号 Token 未配置，无法完成校验");
            return "token not configured";
        }
        if (checkSignature(signature, timestamp, nonce)) {
            log.info("公众号服务器校验通过");
            return echostr == null ? "" : echostr;
        }
        log.warn("公众号服务器校验失败 signature={}", signature);
        return "invalid signature";
    }

    @PostMapping(produces = "application/xml;charset=UTF-8")
    public String handle(@RequestBody String body) {
        try {
            Document doc = parse(body);
            if (doc == null) return "success";

            String toUser = text(doc, "ToUserName");       // 公众号原始 id
            String fromUser = text(doc, "FromUserName");   // 用户 openid
            String msgType = text(doc, "MsgType");
            String encrypt = text(doc, "Encrypt");
            String content = text(doc, "Content");

            log.info("收到公众号消息 msgType={} openid={} 加密={} 内容={}",
                    msgType, maskOpenId(fromUser), !encrypt.isBlank(), brief(content));

            // 后台选了「安全模式/兼容模式」时，微信推的是密文（只有 Encrypt，没有明文 MsgType）。
            // 若服务端未配置 EncodingAESKey，直接返回 success（微信不重试），并在日志里点名原因，
            // 避免给用户回一条莫名其妙的内容。
            if ((msgType == null || msgType.isBlank()) && !encrypt.isBlank()) {
                log.warn("收到加密消息，但服务端未启用加密模式：请在公众号后台把「消息加解密方式」"
                        + "改为【明文模式】，或提供 EncodingAESKey 由服务端开启 AES 解密");
                return "success";
            }

            String reply;
            if ("text".equalsIgnoreCase(msgType)) {
                reply = wechatMpService.answer(fromUser, content);
            } else if ("event".equalsIgnoreCase(msgType)) {
                String ev = text(doc, "Event");
                reply = "subscribe".equalsIgnoreCase(ev)
                        ? wechatMpService.answer(fromUser, "帮助")
                        : null;                                 // 其他事件不回复
            } else {
                reply = "目前只支持文字提问～\n发「帮助」看我都能查什么。";
            }
            if (reply == null || reply.isBlank()) return "success";   // 不回内容
            // 统一附上网站地址（帮助 / 报价问答 / 关注欢迎语 全覆盖）
            reply = reply + "\n\n🌐 完整行情 → " + SITE_URL;
            log.info("公众号回复 openid={} 长度={} 预览={}",
                    maskOpenId(fromUser), reply.length(), brief(reply));
            return textReply(fromUser, toUser, reply);
        } catch (Exception e) {
            log.error("公众号消息处理失败: {}", e.getMessage(), e);
            return "success";   // 返回 success：微信不重试，用户侧无异常提示
        }
    }

    /** 网站地址：公众号每条回复末尾统一露出，引导读者到网页版看完整行情 */
    private static final String SITE_URL = "http://82.156.8.214";

    /* ================= 日志脱敏 ================= */

    private static String maskOpenId(String s) {
        if (s == null || s.isBlank()) return "-";
        return s.length() <= 8 ? s : s.substring(0, 6) + "***" + s.substring(s.length() - 4);
    }

    private static String brief(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 40 ? one : one.substring(0, 40) + "…";
    }

    /* ================= 签名 ================= */

    private boolean checkSignature(String signature, String timestamp, String nonce) {
        if (signature == null || timestamp == null || nonce == null) return false;
        String[] arr = {token, timestamp, nonce};
        Arrays.sort(arr);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(String.join("", arr).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /* ================= XML ================= */

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // 防 XXE
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Document doc, String tag) {
        NodeList nl = doc.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        Element e = (Element) nl.item(0);
        return e.getTextContent() == null ? "" : e.getTextContent();
    }

    private static final Pattern CDATA_END = Pattern.compile("]]>");

    private static String textReply(String toUser, String fromUser, String content) {
        String safe = CDATA_END.matcher(content).replaceAll("]]&gt;");
        if (safe.length() > 1200) safe = safe.substring(0, 1200) + "…";
        return "<xml>"
                + "<ToUserName><![CDATA[" + toUser + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + fromUser + "]]></FromUserName>"
                + "<CreateTime>" + (System.currentTimeMillis() / 1000) + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + safe + "]]></Content>"
                + "</xml>";
    }

    /** 供调试：看一眼配置是否就绪 */
    @GetMapping(value = "/status", produces = "application/json;charset=UTF-8")
    public String status() {
        boolean cfg = token != null && !token.isBlank();
        return "{\"tokenConfigured\":" + cfg + "}";
    }
}
