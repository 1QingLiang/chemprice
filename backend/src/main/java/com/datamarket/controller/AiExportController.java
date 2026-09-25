package com.datamarket.controller;

import com.datamarket.service.ExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AI 导出文件下载入口。
 * <p>
 * 浏览器点链接下载时不会带 Authorization 头，所以这里不依赖 JWT，
 * 而是校验 ExportService 生成的**带签名与有效期的令牌**（30 分钟）。
 * 令牌里含 uuid，文件本身放在 Web 根目录之外，猜不到也列不出来。
 */
@RestController
@RequestMapping("/api/ai/export")
@RequiredArgsConstructor
@Slf4j
public class AiExportController {

    private final ExportService exportService;

    @GetMapping
    public void download(@RequestParam("t") String token, HttpServletResponse response) throws IOException {
        String[] v = exportService.verifyToken(token);
        if (v == null) {
            deny(response, 403, "下载链接已失效（有效期 30 分钟），请在对话里重新发起导出。");
            return;
        }
        Path file = exportService.fileOf(v[0]);
        if (file == null) {
            deny(response, 404, "文件已被清理（保留 2 天），请在对话里重新发起导出。");
            return;
        }
        String encoded = URLEncoder.encode(v[1], StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
        response.getOutputStream().flush();
    }

    private void deny(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + msg + "\"}");
    }
}
