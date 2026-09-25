package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.entity.SysUser;
import com.datamarket.security.PermissionHelper;
import com.datamarket.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 营业执照图片上传。
 * - 上传：登录用户，仅图片、≤5MB，随机文件名（不暴露用户信息），存 Web 根目录之外；
 * - 查看：仅管理员（营业执照是敏感材料，绝不公开 URL），带文件名校验防目录穿越。
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final PermissionHelper permissionHelper;
    private final AuditService auditService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Value("${chemprice.upload.dir:/home/ubuntu/chemprice/uploads}")
    private String uploadDir;

    private static final long MAX_SIZE = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");
    private static final Pattern SAFE_NAME = Pattern.compile("^license_[A-Za-z0-9_]+\\.(jpg|jpeg|png|webp)$");
    private static final Map<String, String> MEDIA = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp");

    /** 上传营业执照（登录用户） */
    @PostMapping("/license")
    public Result<Map<String, Object>> license(@RequestParam("file") MultipartFile file) {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null) return Result.error(401, "请先登录");
        // 实名前置：未实名不允许上传执照（避免浪费工商核验次数）
        Integer rnSt = jdbcTemplate.queryForObject(
                "SELECT IFNULL(realname_status,0) FROM sys_user WHERE id = ?", Integer.class, u.getId());
        if (rnSt == null || rnSt != 1) {
            return Result.error(403, "请先完成实名认证，再上传营业执照（认证页顶部可免费实名）");
        }
        // 上传次数：累计 3 次，超限联系管理员开通
        Long licUsed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE user_id = ? AND action = 'UPLOAD_LICENSE'",
                Long.class, u.getId());
        if (licUsed != null && licUsed >= 3) {
            return Result.error("营业执照上传次数已达上限（3 次），如需重新上传请联系管理员开通");
        }
        if (file == null || file.isEmpty()) return Result.error("请选择图片文件");
        if (file.getSize() > MAX_SIZE) return Result.error("图片不能超过 5MB");

        String orig = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = orig.contains(".") ? orig.substring(orig.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXT.contains(ext)) return Result.error("仅支持 jpg / jpeg / png / webp 图片");

        String name = "license_" + u.getId() + "_" + System.currentTimeMillis()
                + "_" + UUID.randomUUID().toString().substring(0, 6) + "." + ext;
        try {
            Path dir = Paths.get(uploadDir, "license");
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(name).toFile());
        } catch (Exception e) {
            return Result.error("上传失败，请稍后重试");
        }

        try {
            auditService.record("UPLOAD_LICENSE", "BUSINESS", "LICENSE", name,
                    null, null, 1, "INFO", "上传营业执照图片");
        } catch (Exception ignore) { }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", "license/" + name);
        out.put("name", name);

        // AI 识别：提取执照上的公司名与信用代码，供前端自动填入表单（识别失败不影响上传）
        Map<String, Object> ocr = runOcr(Paths.get(uploadDir, "license", name).toFile().getAbsolutePath());
        if (ocr != null) {
            out.put("ocr", ocr);
            // AI 验证：过期（OCR）+ 存续状态（工商接口）双参数，结果直接给用户
            Map<String, Object> biz = new LinkedHashMap<>();
            boolean expired = Boolean.TRUE.equals(ocr.get("expired"));
            String credit = ocr.get("credit_code") == null ? "" : String.valueOf(ocr.get("credit_code"));
            String appCode = System.getenv("ALIYUN_MARKET_APPCODE");
            if (expired) {
                biz.put("pass", false);
                biz.put("message", "营业执照已过期（营业期限至 " + ocr.get("expire_date") + "）");
            } else if (credit.isEmpty() || appCode == null || appCode.isBlank()) {
                biz.put("pass", null);
                biz.put("message", "无法自动核验存续状态，将由管理员人工核验");
            } else {
                try {
                    java.net.URL gsUrl = new java.net.URL("https://taxno.market.alicloudapi.com/lundear/taxno?keyword="
                            + java.net.URLEncoder.encode(credit, "UTF-8") + "&pageSize=10");
                    java.net.HttpURLConnection gconn = (java.net.HttpURLConnection) gsUrl.openConnection();
                    gconn.setRequestProperty("Authorization", "APPCODE " + appCode);
                    gconn.setConnectTimeout(8000);
                    gconn.setReadTimeout(15000);
                    StringBuilder gsb = new StringBuilder();
                    try (java.io.BufferedReader gbr = new java.io.BufferedReader(
                            new java.io.InputStreamReader(gconn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String gline;
                        while ((gline = gbr.readLine()) != null) gsb.append(gline);
                    }
                    if (gsb.length() > 0) {
                        com.fasterxml.jackson.databind.ObjectMapper gom = new com.fasterxml.jackson.databind.ObjectMapper();
                        Map<String, Object> gj = gom.readValue(gsb.toString(), Map.class);
                        int gCode = gj.get("code") instanceof Number gn ? gn.intValue() : -1;
                        if (gCode == 2) {
                            biz.put("pass", false);
                            biz.put("message", "工商系统中未查询到该公司，请核对信用代码");
                        } else if (gCode == 0) {
                            Map<String, Object> gres = (Map<String, Object>) gj.get("result");
                            List<Map<String, Object>> gitems = gres == null ? null
                                    : (List<Map<String, Object>>) gres.get("items");
                            String regStatus = null;
                            if (gitems != null) {
                                for (Map<String, Object> it : gitems) {
                                    if (credit.equalsIgnoreCase(String.valueOf(it.get("creditCode")))) {
                                        regStatus = String.valueOf(it.get("regStatus")); break;
                                    }
                                }
                            }
                            if (regStatus == null) {
                                biz.put("pass", false);
                                biz.put("message", "工商系统中未查询到该公司，请核对信用代码");
                            } else if (regStatus.contains("存续") || regStatus.contains("在业") || regStatus.contains("正常")) {
                                biz.put("pass", true);
                                biz.put("message", "工商登记状态：" + regStatus + "，营业期限在有效期内");
                                biz.put("regStatus", regStatus);
                            } else {
                                biz.put("pass", false);
                                biz.put("message", "该公司登记状态为「" + regStatus + "」");
                                biz.put("regStatus", regStatus);
                            }
                        } else {
                            biz.put("pass", null);
                            biz.put("message", "存续状态查询异常，将由管理员人工核验");
                        }
                    } else {
                        biz.put("pass", null);
                        biz.put("message", "存续状态查询失败，将由管理员人工核验");
                    }
                } catch (Exception e) {
                    biz.put("pass", null);
                    biz.put("message", "存续状态查询失败，将由管理员人工核验");
                }
            }
            out.put("business", biz);
        }

        return Result.ok(out);
    }

    /** 调用本地 OCR 脚本识别执照内容（RapidOCR；失败返回 null，不阻塞上传） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runOcr(String imgPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "/home/ubuntu/chemprice/ocr_license.py", imgPath);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            if (!proc.waitFor(90, TimeUnit.SECONDS)) { proc.destroyForcibly(); return null; }
            if (sb.length() == 0) return null;
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(sb.toString(), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 查看营业执照（仅管理员） */
    @GetMapping("/license/{name}")
    public org.springframework.http.ResponseEntity<byte[]> view(@PathVariable String name) {
        SysUser u = permissionHelper.getCurrentUser();
        if (u == null || !"ADMIN".equals(u.getRole())) {
            return org.springframework.http.ResponseEntity.status(403)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":403,\"message\":\"仅管理员可查看\"}".getBytes());
        }
        if (!SAFE_NAME.matcher(name).matches()) {
            return org.springframework.http.ResponseEntity.status(400)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":400,\"message\":\"文件名不合法\"}".getBytes());
        }
        try {
            Path f = Paths.get(uploadDir, "license", name);
            if (!Files.exists(f)) {
                return org.springframework.http.ResponseEntity.status(404)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":404,\"message\":\"文件不存在\"}".getBytes());
            }
            String ext = name.substring(name.lastIndexOf('.') + 1);
            byte[] bytes = Files.readAllBytes(f);
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(MEDIA.getOrDefault(ext, "application/octet-stream")))
                    .body(bytes);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":500,\"message\":\"读取失败\"}".getBytes());
        }
    }
}
