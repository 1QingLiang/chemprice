package com.datamarket.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 支付宝实名支付：手机网站支付（alipay.trade.wap.pay）。
 * 凭据从环境变量读取（不落代码）：
 *   ALIPAY_APP_ID        开放平台应用的 AppID
 *   ALIPAY_PRIVATE_KEY   应用私钥（PKCS8）
 *   ALIPAY_PUBLIC_KEY    支付宝公钥
 *   ALIPAY_NOTIFY_URL    异步回调地址（公网可访问，如 https://域名/api/realname/alipay/notify）
 *   ALIPAY_RETURN_URL    支付完成跳回地址（可选，如 https://域名/supplier-verify）
 *   ALIPAY_GATEWAY       网关（默认 https://openapi.alipay.com/gateway.do）
 */
@Service
public class AlipayService {

    @Value("${chemprice.upload.dir:/home/ubuntu/chemprice/uploads}")
    private String uploadDir; // 占位，避免无字段告警；实际配置全走环境变量

    private volatile AlipayClient client;

    public boolean configured() {
        return notBlank(env("ALIPAY_APP_ID")) && notBlank(env("ALIPAY_PRIVATE_KEY"))
                && notBlank(env("ALIPAY_PUBLIC_KEY")) && notBlank(notifyUrl());
    }

    public String notifyUrl() { return env("ALIPAY_NOTIFY_URL"); }
    public String returnUrl() { return env("ALIPAY_RETURN_URL"); }

    /** 生成支付宝收银台跳转 URL（用户在新页面完成支付） */
    public String createWapPay(String orderNo, String amount, String subject) throws AlipayApiException {
        AlipayClient c = client();
        AlipayTradeWapPayRequest req = new AlipayTradeWapPayRequest();
        req.setNotifyUrl(notifyUrl());
        String ret = returnUrl();
        if (notBlank(ret)) req.setReturnUrl(ret);
        try {
            String biz = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(java.util.Map.of("out_trade_no", orderNo,
                            "total_amount", amount, "subject", subject,
                            "product_code", "QUICK_WAP_WAY"));
            req.setBizContent(biz);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AlipayApiException("bizContent 序列化失败");
        }
        // GET 形式：返回可直接跳转的完整 URL
        return c.pageExecute(req, "GET").getBody();
    }

    /** 异步回调验签 */
    public boolean verifyNotify(Map<String, String> params) throws AlipayApiException {
        return AlipaySignature.rsaCheckV1(params, env("ALIPAY_PUBLIC_KEY"), "UTF-8", "RSA2");
    }

    private AlipayClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new DefaultAlipayClient(
                            env("ALIPAY_GATEWAY") != null ? env("ALIPAY_GATEWAY") : "https://openapi.alipay.com/gateway.do",
                            env("ALIPAY_APP_ID"), env("ALIPAY_PRIVATE_KEY"),
                            "json", "UTF-8", env("ALIPAY_PUBLIC_KEY"), "RSA2");
                }
            }
        }
        return client;
    }

    private String env(String k) { return System.getenv(k); }
    private boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
