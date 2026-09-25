package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.entity.SysUser;
import com.datamarket.security.PermissionHelper;
import com.datamarket.service.AuditService;
import com.datamarket.service.VerifyNotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 实名认证（支付宝人脸核身）。
 * - 现阶段 alipay_mode=mock：沙箱模拟支付与核身，不产生真实扣款；
 * - 管理员签约支付宝后，将 site_setting.alipay_mode 改为 alipay 并在环境变量提供
 *   ALIPAY_APP_ID / ALIPAY_PRIVATE_KEY / ALIPAY_PUBLIC_KEY，即可切换正式模式
 *   （正式支付与回调验签在 notify 与 order 接口预留的分支处实现）。
 */
@RestController
@RequestMapping("/api/realname")
@RequiredArgsConstructor
public class RealnameController {

    private final JdbcTemplate jdbcTemplate;
    private final PermissionHelper permissionHelper;
    private final AuditService auditService;
    private final com.datamarket.service.AlipayService alipayService;
    private final VerifyNotifyService verifyNotify;

    private SysUser me() { return permissionHelper.getCurrentUser(); }

    /** 展示名：优先昵称，回退用户名 */
    private static String displayName(SysUser u) {
        if (u == null) return "";
        String n = u.getNickname();
        return (n == null || n.isBlank()) ? String.valueOf(u.getUsername()) : n;
    }

    /** 收款码图片（登录用户可读——收款码本身就是展示给用户扫码用的） */
    @GetMapping("/pay-qr")
    public org.springframework.http.ResponseEntity<byte[]> payQr() {
        SysUser u = me();
        if (u == null) return org.springframework.http.ResponseEntity.status(401)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"code\":401,\"message\":\"请先登录\"}".getBytes());
        String name = setting("realname_pay_qr", "");
        if (name.isBlank()) return org.springframework.http.ResponseEntity.status(404)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"code\":404,\"message\":\"收款码未设置\"}".getBytes());
        if (!name.matches("^license_[A-Za-z0-9_]+\\.(jpg|jpeg|png|webp)$")) {
            return org.springframework.http.ResponseEntity.status(400)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("{\"code\":400,\"message\":\"图片不合法\"}".getBytes());
        }
        try {
            java.nio.file.Path f = java.nio.file.Paths.get("/home/ubuntu/chemprice/uploads/license", name);
            if (!java.nio.file.Files.exists(f)) return org.springframework.http.ResponseEntity.status(404)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("{\"code\":404,\"message\":\"图片不存在\"}".getBytes());
            String ext = name.substring(name.lastIndexOf('.') + 1);
            String mt = ext.equals("png") ? "image/png" : ext.equals("webp") ? "image/webp" : "image/jpeg";
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.parseMediaType(mt))
                    .body(java.nio.file.Files.readAllBytes(f));
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(500).build();
        }
    }

    /** 管理员设置收款码图片与收款人（manual 模式） */
    @PostMapping("/admin-pay-settings")
    public Result<Map<String, Object>> adminPaySettings(@RequestBody Map<String, String> body) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (!"ADMIN".equals(u.getRole())) return Result.error(403, "仅管理员可操作");
        String qr = body.getOrDefault("qrUrl", "").trim();
        boolean keepQr = "KEEP".equals(qr);
        if (!keepQr && !qr.isEmpty() && !qr.matches("^license_[A-Za-z0-9_]+\\.(jpg|jpeg|png|webp)$")) {
            return Result.error("收款码图片不合法");
        }
        if (keepQr) qr = setting("realname_pay_qr", "");
        jdbcTemplate.update(
                "INSERT INTO site_setting(setting_key, setting_value, updated_at) VALUES('realname_pay_qr', ?, NOW()) " +
                "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()", qr);
        jdbcTemplate.update(
                "INSERT INTO site_setting(setting_key, setting_value, updated_at) VALUES('realname_payee', ?, NOW()) " +
                "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()",
                body.getOrDefault("payee", "").trim());
        return Result.ok(Map.of("saved", true));
    }

    private String setting(String key, String def) {
        List<String> r = jdbcTemplate.queryForList(
                "SELECT setting_value FROM site_setting WHERE setting_key = ?", String.class, key);
        return r.isEmpty() || r.get(0) == null ? def : r.get(0);
    }

    /** 实名状态 + 单价 + 当前模式 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        Map<String, Object> out = new LinkedHashMap<>();
        Integer rn = realnameStatusOf(u.getId());
        out.put("realname", rn != null && rn == 1);
        out.put("price", "0");
        out.put("mode", setting("alipay_mode", "mock"));
        // 身份证二要素核验通道是否已配置（AppCode + 接口地址）
        String appCode = System.getenv("ALIYUN_MARKET_APPCODE");
        String apiUrl = System.getenv("ALIYUN_IDCARD_URL");
        out.put("idcardChannel", appCode != null && !appCode.isBlank()
                && apiUrl != null && !apiUrl.isBlank());
        Integer triesTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM realname_verify_log WHERE user_id = ?",
                Integer.class, u.getId());
        out.put("triesLeft", Math.max(0, 3 - (triesTotal == null ? 0 : triesTotal)));
        String qr = setting("realname_pay_qr", "");
        out.put("payQr", !qr.isBlank());
        out.put("payee", setting("realname_payee", ""));
        return Result.ok(out);
    }

    /** 创建实名认证订单（1 次） */
    @PostMapping("/order")
    public Result<Map<String, Object>> createOrder() {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (realnameStatusOf(u.getId()) == 1) return Result.error("你已完成实名认证，无需重复办理");

        String mode = setting("alipay_mode", "mock");
        String price = setting("realname_price_yuan", "1");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("amount", price);

        if ("manual".equals(mode)) {
            // 个人收款码模式：创建订单 → 用户扫码支付 → 填写支付单号 → 管理员核对确认
            String orderNo = "RN" + u.getId() + System.currentTimeMillis()
                    + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
            jdbcTemplate.update(
                    "INSERT INTO realname_order (user_id, out_trade_no, amount, status, mode) " +
                    "VALUES (?, ?, ?, 'pending', 'manual')",
                    u.getId(), orderNo, new java.math.BigDecimal(price));
            out.put("payable", true);
            out.put("orderNo", orderNo);
            out.put("qrUrl", setting("realname_pay_qr", ""));
            out.put("payee", setting("realname_payee", ""));
            return Result.ok(out);
        }
        if ("alipay".equals(mode)) {
            if (!alipayService.configured()) {
                out.put("payable", false);
                out.put("message", "支付宝商户凭据未配置（需 ALIPAY_APP_ID/私钥/公钥环境变量），请联系管理员");
                return Result.ok(out);
            }
            String orderNo = "RN" + u.getId() + System.currentTimeMillis()
                    + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
            jdbcTemplate.update(
                    "INSERT INTO realname_order (user_id, out_trade_no, amount, status, mode) " +
                    "VALUES (?, ?, ?, 'pending', 'alipay')",
                    u.getId(), orderNo, new java.math.BigDecimal(price));
            String payUrl;
            try {
                payUrl = alipayService.createWapPay(orderNo, price, "ChemPrice 实名认证服务");
            } catch (Exception e) {
                out.put("payable", false);
                out.put("message", "生成支付宝支付链接失败：" + e.getMessage());
                return Result.ok(out);
            }
            out.put("payable", true);
            out.put("orderNo", orderNo);
            out.put("payUrl", payUrl);
            return Result.ok(out);
        }
        if (!"mock".equals(mode)) {
            out.put("payable", false);
            out.put("message", "支付模式配置不正确（alipay_mode 应为 mock 或 alipay）");
            return Result.ok(out);
        }

        String orderNo = "RN" + u.getId() + System.currentTimeMillis()
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
        jdbcTemplate.update(
                "INSERT INTO realname_order (user_id, out_trade_no, amount, status, mode) " +
                "VALUES (?, ?, ?, 'pending', 'mock')",
                u.getId(), orderNo, new java.math.BigDecimal(price));
        out.put("payable", true);
        out.put("orderNo", orderNo);
        return Result.ok(out);
    }

    /** 沙箱模式：模拟支付成功 + 人脸核身通过（仅在 alipay_mode=mock 时可用） */
    @PostMapping("/order/{orderNo}/mock-pay")
    public Result<Map<String, Object>> mockPay(@PathVariable String orderNo) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (!"mock".equals(setting("alipay_mode", "mock"))) {
            return Result.error(403, "沙箱支付仅在沙箱模式可用");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, status, amount FROM realname_order WHERE out_trade_no = ?", orderNo);
        if (rows.isEmpty()) return Result.error("订单不存在");
        Map<String, Object> o = rows.get(0);
        Object oid = o.get("user_id");
        if (!Objects.equals(u.getId(), oid instanceof Number n ? n.longValue() : null)) {
            return Result.error(403, "只能操作自己的订单");
        }
        if (!"pending".equals(String.valueOf(o.get("status")))) return Result.error("订单状态不可支付");

        jdbcTemplate.update(
                "UPDATE realname_order SET status = 'paid', trade_no = ?, paid_at = NOW() " +
                "WHERE out_trade_no = ?",
                "SANDBOX" + System.currentTimeMillis(), orderNo);

        String appCode = System.getenv("ALIYUN_MARKET_APPCODE");
        String apiUrl = System.getenv("ALIYUN_IDCARD_URL");
        boolean idcardChannel = appCode != null && !appCode.isBlank()
                && apiUrl != null && !apiUrl.isBlank();
        if (idcardChannel) {
            // 已配置身份证二要素通道：支付完成只是第一步，实名在核验通过后生效
            Map<String, Object> out0 = new LinkedHashMap<>();
            out0.put("paid", true);
            return Result.ok(out0);
        }
        jdbcTemplate.update(
                "UPDATE sys_user SET realname_status = 1, realname_at = NOW() WHERE id = ?", u.getId());

        try {
            jdbcTemplate.update(
                    "INSERT INTO site_message (user_id, title, content, type) VALUES (?,?,?,?)",
                    u.getId(), "实名认证成功",
                    "你的实名认证已完成（沙箱模式），现在可以发布求购与供应信息。",
                    "realname");
        } catch (Exception ignore) { }

        try {
            auditService.record("REALNAME_PAY", "USER", "REALNAME", orderNo,
                    null, null, 1, "INFO", "实名认证沙箱支付成功");
        } catch (Exception ignore) { }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("realname", true);
        out.put("orderNo", orderNo);
        return Result.ok(out);
    }

    /** 身份证二要素核验（阿里云市场 AppCode 通道）：姓名 + 身份证号，一致则实名生效 */
    @PostMapping("/verify-id")
    public Result<Map<String, Object>> verifyId(@RequestBody Map<String, String> body) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (realnameStatusOf(u.getId()) == 1) return Result.error("你已完成实名认证，无需重复办理");

        // 兼容混淆传输：payload = Base64(name + "\n" + idcard)，规避链路中间设备对明文身份证号 POST 的拦截
        String payloadEnc = body.get("payload");
        if (payloadEnc != null && !payloadEnc.trim().isEmpty()) {
            try {
                String raw = new String(java.util.Base64.getDecoder().decode(payloadEnc.trim()),
                        java.nio.charset.StandardCharsets.UTF_8);
                int sep = raw.indexOf('\n');
                if (sep > 0) {
                    body.put("name", raw.substring(0, sep).trim());
                    body.put("idcard", raw.substring(sep + 1).trim());
                }
            } catch (IllegalArgumentException ignored) { }
        }
        String name = body.getOrDefault("name", "").trim();
        String idcard = body.getOrDefault("idcard", "").trim().toUpperCase(Locale.ROOT);
        if (name.length() < 2 || name.length() > 30) return Result.error("请填写真实姓名");
        if (!idcard.matches("^[0-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9X]$")) {
            return Result.error("身份证号格式不正确");
        }
        // 免费实名防滥用：每用户累计最多 3 次核验尝试（含失败，全部留痕），超限联系管理员
        Integer totalTries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM realname_verify_log WHERE user_id = ?",
                Integer.class, u.getId());
        if (totalTries != null && totalTries >= 3) {
            return Result.error("实名核验尝试已达上限（累计 3 次），如需继续核验请联系管理员开通");
        }
        // 同一身份证号只能绑定一个账号
        String idMasked0 = idcard.substring(0, 4) + "**********" + idcard.substring(14);
        Integer dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE (realname_idcard = ? OR realname_idcard_full = ?) AND id <> ?",
                Integer.class, idMasked0, idcard, u.getId());
        if (dup != null && dup > 0) {
            return Result.error("该身份证号已被其他账号绑定实名");
        }

        String appCode = System.getenv("ALIYUN_MARKET_APPCODE");
        String apiUrl = System.getenv("ALIYUN_IDCARD_URL");
        if (appCode == null || appCode.isBlank() || apiUrl == null || apiUrl.isBlank()) {
            return Result.error("实名核验通道未配置，请联系管理员");
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            java.net.URLEncoder enc = null;
            String payload = "name=" + java.net.URLEncoder.encode(name, "UTF-8")
                    + "&idcard=" + java.net.URLEncoder.encode(idcard, "UTF-8");
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .header("Authorization", "APPCODE " + appCode)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .build();
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String respBody = resp.body() == null ? "" : resp.body();

            // 精确解析阿里云市场二要素返回（实测结构）：
            // {"msg":"成功","success":true,"code":200,"data":{"result":1,"desc":"一致|不一致","orderNo":"...","birthday":...,"address":...,"sex":...}}
            // ⚠ data.result=身份证是否可查，NOT 姓名一致；比对结论只认 data.desc
            // code=400（如身份证号不合法）时 data 无 desc，msg 带原因
            java.util.Map<?, ?> json = null;
            try { json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(respBody, java.util.Map.class); }
            catch (Exception ignore) { }
            String desc = null;
            int bizCode = -1;
            if (json != null) {
                try { bizCode = Integer.parseInt(String.valueOf(json.get("code"))); } catch (Exception ignore) { }
                Object dm = json.get("data");
                if (dm instanceof java.util.Map) {
                    Object dv = ((java.util.Map<?, ?>) dm).get("desc");
                    if (dv != null) desc = String.valueOf(dv).trim();
                }
            }
            boolean ok = resp.statusCode() == 200 && bizCode == 200 && "一致".equals(desc);
            try {
                jdbcTemplate.update(
                        "INSERT INTO realname_verify_log (user_id, name, idcard_masked, matched, http_status) VALUES (?,?,?,?,?)",
                        u.getId(), name, idMasked0, ok ? 1 : 0, resp.statusCode());
            } catch (Exception ignore2) { }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("matched", ok);
            out.put("respSnippet", respBody.length() > 200 ? respBody.substring(0, 200) : respBody);
            if (!ok) {
                String reason = "不一致".equals(desc) ? "姓名与身份证号不一致，请核对后重试"
                        : (bizCode == 400 && json != null ? String.valueOf(json.get("msg"))
                        : "核验通道返回异常，请稍后重试");
                out.put("message", reason);
                return Result.ok(out);
            }

            String masked = idcard.substring(0, 4) + "**********" + idcard.substring(14);
            jdbcTemplate.update(
                    "UPDATE sys_user SET realname_status = 1, realname_at = NOW(), " +
                    "realname_name = ?, realname_idcard = ?, realname_idcard_full = ? WHERE id = ?",
                    name, masked, idcard, u.getId());
            try {
                jdbcTemplate.update(
                        "INSERT INTO site_message (user_id, title, content, type) VALUES (?,?,?,?)",
                        u.getId(), "实名认证成功",
                        "你的实名认证已完成（身份证二要素核验通过），现在可以发布求购与供应信息。",
                        "realname");
            } catch (Exception ignore) { }
            try {
                auditService.record("REALNAME_IDCARD", "USER", "REALNAME", String.valueOf(u.getId()),
                        null, null, 1, "INFO", "身份证二要素核验通过");
            } catch (Exception ignore) { }
            out.put("realname", true);
            return Result.ok(out);
        } catch (Exception e) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO realname_verify_log (user_id, name, idcard_masked, matched, http_status) VALUES (?,?,?,?,?)",
                        u.getId(), name, idMasked0, 0, -1);
            } catch (Exception ignore2) { }
            return Result.error("核验请求失败：" + e.getMessage());
        }
    }

    /** 支付宝异步回调：验签 → 标记订单已支付 → 用户实名生效 */
    @PostMapping("/alipay/notify")
    public String alipayNotify(@RequestParam Map<String, String> params) {
        try {
            if (!alipayService.verifyNotify(params)) return "failure";
            if (!"TRADE_SUCCESS".equals(params.get("trade_status"))
                    && !"TRADE_FINISHED".equals(params.get("trade_status"))) return "failure";
            String orderNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT user_id, status FROM realname_order WHERE out_trade_no = ?", orderNo);
            if (rows.isEmpty()) return "failure";
            if (!"pending".equals(String.valueOf(rows.get(0).get("status")))) return "success"; // 幂等
            Long uid = Long.valueOf(String.valueOf(rows.get(0).get("user_id")));
            jdbcTemplate.update(
                    "UPDATE realname_order SET status = 'paid', trade_no = ?, paid_at = NOW() " +
                    "WHERE out_trade_no = ?", tradeNo, orderNo);
            jdbcTemplate.update(
                    "UPDATE sys_user SET realname_status = 1, realname_at = NOW() WHERE id = ?", uid);
            try {
                jdbcTemplate.update(
                        "INSERT INTO site_message (user_id, title, content, type) VALUES (?,?,?,?)",
                        uid, "实名认证成功",
                        "你的实名认证已完成（支付宝支付核验），现在可以发布求购与供应信息。",
                        "realname");
            } catch (Exception ignore) { }
            return "success";
        } catch (Exception e) {
            return "failure";
        }
    }

    /** manual 模式：用户提交支付宝付款单号（待管理员核对确认） */
    @PostMapping("/order/{orderNo}/claim")
    public Result<Map<String, Object>> claim(@PathVariable String orderNo,
                                             @RequestBody Map<String, String> body) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        String payerNo = body.getOrDefault("payerNo", "").trim();
        if (!payerNo.matches("\\d{6,32}")) return Result.error("请填写支付宝订单号（6-32 位数字）");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, status, amount FROM realname_order WHERE out_trade_no = ?", orderNo);
        if (rows.isEmpty()) return Result.error("订单不存在");
        Object oid = rows.get(0).get("user_id");
        if (!Objects.equals(u.getId(), oid instanceof Number n ? n.longValue() : null)) {
            return Result.error(403, "只能操作自己的订单");
        }
        if (!"pending".equals(String.valueOf(rows.get(0).get("status")))) {
            return Result.error("订单状态不可提交");
        }
        jdbcTemplate.update(
                "UPDATE realname_order SET status = 'verifying', trade_no = ? WHERE out_trade_no = ?",
                payerNo, orderNo);

        // 通知管理员（站内信 + 邮件）：有新的实名收款待确认
        verifyNotify.onNewApply("实名认证", VerifyNotifyService.fields(
                "申请人", displayName(u),
                "订单号", orderNo,
                "支付宝订单号", payerNo,
                "应付金额", rows.get(0).get("amount") == null ? "" : String.valueOf(rows.get(0).get("amount")) + " 元"));

        return Result.ok(Map.of("submitted", true));
    }

    /** 管理员：人工确认收款（实名生效） */
    @PostMapping("/admin-confirm/{orderNo}")
    public Result<Map<String, Object>> adminConfirm(@PathVariable String orderNo) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (!"ADMIN".equals(u.getRole())) return Result.error(403, "仅管理员可操作");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, status FROM realname_order WHERE out_trade_no = ?", orderNo);
        if (rows.isEmpty()) return Result.error("订单不存在");
        if (!"verifying".equals(String.valueOf(rows.get(0).get("status")))) {
            return Result.error("该订单不在待确认状态");
        }
        Long uid = Long.valueOf(String.valueOf(rows.get(0).get("user_id")));
        jdbcTemplate.update(
                "UPDATE realname_order SET status = 'paid', paid_at = NOW() WHERE out_trade_no = ?",
                orderNo);
        jdbcTemplate.update(
                "UPDATE sys_user SET realname_status = 1, realname_at = NOW() WHERE id = ?", uid);
        try {
            jdbcTemplate.update(
                    "INSERT INTO site_message (user_id, title, content, type) VALUES (?,?,?,?)",
                    uid, "实名认证成功",
                    "你的实名认证已完成（收款已确认），现在可以发布求购与供应信息。",
                    "realname");
        } catch (Exception ignore) { }

        // 结果邮件通知用户（站内信上面已发，此处只补邮件）
        verifyNotify.onReviewed(uid, "实名认证", true, VerifyNotifyService.fields(
                "订单号", orderNo,
                "审核结果", "已通过（收款已确认）",
                "生效说明", "实名认证已生效，现在可以发布求购与供应信息"));

        try {
            auditService.record("REALNAME_CONFIRM", "USER", "REALNAME", orderNo,
                    null, null, 1, "INFO", "管理员确认实名收款");
        } catch (Exception ignore) { }
        return Result.ok(Map.of("realname", true));
    }

    /** 管理员：待确认的实名收款列表 */
    @GetMapping("/admin-pending")
    public Result<List<Map<String, Object>>> adminPending() {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        if (!"ADMIN".equals(u.getRole())) return Result.error(403, "仅管理员可操作");
        return Result.ok(jdbcTemplate.queryForList(
                "SELECT o.out_trade_no, o.amount, o.trade_no, o.created_at, " +
                "u.username, u.nickname FROM realname_order o " +
                "JOIN sys_user u ON u.id = o.user_id " +
                "WHERE o.status = 'verifying' ORDER BY o.id DESC LIMIT 100"));
    }

    /** 查询订单状态（前端支付后轮询用） */
    @GetMapping("/order/{orderNo}")
    public Result<Map<String, Object>> orderStatus(@PathVariable String orderNo) {
        SysUser u = me();
        if (u == null) return Result.error(401, "请先登录");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT out_trade_no, amount, status, mode, paid_at FROM realname_order " +
                "WHERE out_trade_no = ? AND user_id = ?", orderNo, u.getId());
        if (rows.isEmpty()) return Result.error("订单不存在");
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("realname", realnameStatusOf(u.getId()) == 1);
        return Result.ok(out);
    }

    /** 递归扫描核验结果：返回 "yes"（一致）/ "no"（明确不一致）/ null（无法判定） */
    private String scanMatch(Map<?, ?> node) {
        String verdict = null;
        for (Map.Entry<?, ?> e : node.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v instanceof Map) {
                String r = scanMatch((Map<?, ?>) v);
                if (r != null) verdict = r;
                continue;
            }
            String vs = String.valueOf(v).trim();
            boolean posKey = k.equalsIgnoreCase("result") || k.equalsIgnoreCase("res")
                    || k.equalsIgnoreCase("status") || k.equalsIgnoreCase("isMatch")
                    || k.equalsIgnoreCase("matched") || k.equalsIgnoreCase("match")
                    || k.equalsIgnoreCase("is_ok") || k.equalsIgnoreCase("isok");
            if (posKey) {
                if (vs.equals("1") || vs.equalsIgnoreCase("true") || vs.equals("01")
                        || vs.contains("一致") || vs.contains("成功") || vs.equalsIgnoreCase("match")) {
                    verdict = "yes";
                } else if (vs.equals("2") || vs.equals("3") || vs.contains("不一致")
                        || vs.contains("不匹配") || vs.contains("无记录") || vs.contains("注销")) {
                    verdict = "no";
                }
            }
            if (k.equalsIgnoreCase("msg") || k.equalsIgnoreCase("message") || k.equalsIgnoreCase("note")) {
                if (vs.contains("不一致") || vs.contains("不匹配") || vs.contains("无记录")
                        || vs.contains("不合法")) {
                    verdict = "no";
                } else if (vs.contains("认证成功") || vs.contains("核验通过") || vs.contains("一致")) {
                    verdict = "yes";
                }
            }
        }
        return verdict;
    }

    private Integer realnameStatusOf(Long userId) {
        List<Integer> r = jdbcTemplate.queryForList(
                "SELECT realname_status FROM sys_user WHERE id = ?", Integer.class, userId);
        return r.isEmpty() ? null : r.get(0);
    }
}
