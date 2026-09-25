package com.datamarket.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小米 MiMo（OpenAI 兼容端点）客户端。
 * 只做一件事：把 {system, user} 两段文本发给模型，拿回模型文本输出。
 * API Key 从环境变量 AI_MIMO_KEY 读取（不落盘），未配置时返回可读错误。
 */
@Component
@Slf4j
public class AiMimoClient {

    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";
    private static final String DEFAULT_MODEL = "mimo-v2.6-flash";
    /* 意图识别用的模型（关思考）。用户要求全平台统一用 flash —— 比 v2.5 快且同档稳定。 */
    private static final String DEFAULT_FAST_MODEL = "mimo-v2.6-flash";

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final String fastModel;

    /* 端点是否不支持 thinking 参数（首次遇到 400 后置位，避免反复试错） */
    private volatile boolean thinkingUnsupported = false;

    /* 最近一次调用的 Token 用量 [prompt, completion, total]。
       用 ThreadLocal 承载而不是改 chat() 返回值，避免动到全部调用点；
       同一次问答内的多次调用（意图 + 答案，含重试）会累加，上层一次性取走。 */
    private final ThreadLocal<int[]> usageAcc = ThreadLocal.withInitial(() -> new int[3]);

    /** 取走并清零累计的 Token 用量（[prompt, completion, total]） */
    public int[] takeLastUsage() {
        int[] u = usageAcc.get();
        int[] r = {u[0], u[1], u[2]};
        u[0] = 0;
        u[1] = 0;
        u[2] = 0;
        return r;
    }

    /** 当前使用的模型名（记录用） */
    public String modelName() {
        return model;
    }

    public AiMimoClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.apiKey = System.getenv("AI_MIMO_KEY");
        this.model = System.getenv("AI_MIMO_MODEL") != null ? System.getenv("AI_MIMO_MODEL") : DEFAULT_MODEL;
        this.fastModel = System.getenv("AI_MIMO_FAST_MODEL") != null
                ? System.getenv("AI_MIMO_FAST_MODEL") : DEFAULT_FAST_MODEL;
        String baseUrl = System.getenv("AI_MIMO_BASE_URL") != null ? System.getenv("AI_MIMO_BASE_URL") : DEFAULT_BASE_URL;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 单轮文本对话（带一次自动重试，抵御模型服务偶发抖动/超时）。
     * 默认**开启**模型思考（适合需要推理的生成任务）。
     *
     * @param system     系统提示词
     * @param user       用户输入
     * @param maxTokens  输出上限
     * @return 模型输出文本；若模型无 content 返回 null
     * @throws RuntimeException 重试后仍失败时抛出（调用方兜底转用户可读信息）
     */
    public String chat(String system, String user, int maxTokens) {
        return chat(system, user, maxTokens, false);
    }

    /**
     * 单轮文本对话，可关闭模型「思考」（thinking）。
     * <p>MiMo 属思考型模型，思考内容与正式回答**共用 max_tokens 预算**：
     * 一旦思考吃满预算，content 会为空 → 输出解析失败。
     * 意图识别、事实转述这类任务不需要推理，关闭思考后耗时从十几秒降到 1~2 秒，效果不变。
     *
     * @param disableThinking true = 关闭思考（快，适合分类/转述类任务）
     */
    public String chat(String system, String user, int maxTokens, boolean disableThinking) {
        return chatInternal(system, user, maxTokens, disableThinking, this.model);
    }

    /**
     * 快速通道：**意图识别专用** —— 用小模型（默认 mimo-v2.5）且关闭思考。
     * <p>实测同一分类任务 pro 需 2.7s、小模型仅 1.0s，判定结果一致；
     * 意图识别只是把问句映射成工具名，不需要 pro。答案生成仍走 pro
     * （实测换成小模型反而从 8.9s 涨到 14.2s，因为它输出更啰嗦）。
     */
    public String chatFast(String system, String user, int maxTokens) {
        return chatInternal(system, user, maxTokens, true, this.fastModel);
    }

    private String chatInternal(String system, String user, int maxTokens,
                                boolean disableThinking, String useModel) {
        if (!isConfigured()) {
            throw new IllegalStateException("服务端未配置 AI_MIMO_KEY，AI 问答暂不可用");
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return doChat(system, user, maxTokens, disableThinking, useModel);
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                String errBody = e.getResponseBodyAsString();
                log.warn("MiMo 接口返回 {} (attempt {}): {}", e.getStatusCode(), attempt, truncate(errBody, 200));
                last = new RuntimeException("AI 模型服务异常(" + e.getStatusCode() + ")，请稍后再试");
                if (code >= 400 && code < 500 && code != 429) break; // 参数/鉴权错误重试无意义
                sleepQuiet(1200L * (attempt + 1));
            } catch (Exception e) {
                log.warn("MiMo 请求失败 (attempt {}): {}", attempt, e.getMessage());
                last = new RuntimeException("AI 模型连接失败，请稍后再试");
                sleepQuiet(1200L * (attempt + 1));
            }
        }
        throw last != null ? last : new RuntimeException("AI 模型服务异常，请稍后再试");
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String doChat(String system, String user, int maxTokens,
                          boolean disableThinking, String useModel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", useModel);
        body.put("temperature", 0.2);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));
        if (disableThinking && !thinkingUnsupported) {
            body.put("thinking", Map.of("type", "disabled"));
        }

        String raw;
        try {
            raw = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // 兼容兜底：端点若不认 thinking 参数（400），本次去掉该参数重发，并记住不再尝试
            if (disableThinking && !thinkingUnsupported && e.getStatusCode().value() == 400) {
                log.warn("模型端点不支持 thinking 参数，改为不传该参数（后续不再尝试）: {}",
                        truncate(e.getResponseBodyAsString(), 160));
                thinkingUnsupported = true;
                body.remove("thinking");
                raw = restClient.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
            } else {
                throw e;
            }
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            // 记录 Token 用量（放在 content 判空之前：被思考截断的那次也真实消耗了 Token）
            JsonNode usage = root.path("usage");
            int[] acc = usageAcc.get();
            acc[0] += usage.path("prompt_tokens").asInt(0);
            acc[1] += usage.path("completion_tokens").asInt(0);
            acc[2] += usage.path("total_tokens").asInt(0);

            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().trim().isEmpty()) {
                log.warn("MiMo 返回无 content（finish_reason={}，max_tokens={}，疑似思考占满预算）: {}",
                        root.path("choices").path(0).path("finish_reason").asText(""),
                        maxTokens, truncate(raw, 300));
                return null;
            }
            return content.asText().trim();
        } catch (Exception e) {
            log.warn("MiMo 响应解析失败: {}", truncate(raw, 400));
            throw new RuntimeException("AI 模型响应解析失败，请稍后再试");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
