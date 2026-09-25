package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.service.ai.AiChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 智能问答入口。
 * 安全：/api/ai/** 受 SecurityConfig 的 anyRequest().authenticated() 保护，必须携带 JWT；
 * 全部数据查询在 AiChatService 内以当前登录用户身份执行（data_permission 强约束）。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * 发送一个问题，返回 AI 回答。
     * 响应 data 结构：
     * { kind: answer|clarify|error,
     *   reply: string,             // answer 时的中文回答
     *   chart: {name,dates,values} // 走势图数据（趋势类问题）
     *   quotes: [报价行]            // 可渲染成小表格的报价列表
     *   hint: string,              // clarify/error 时的提示文本
     *   options: [string] }        // 商品歧义候选（clarify 时可点击）
     */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String message = body == null ? "" : body.getOrDefault("message", "");
        return Result.ok(aiChatService.chat(message));
    }
}
