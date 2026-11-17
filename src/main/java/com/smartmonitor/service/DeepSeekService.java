package com.smartmonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek V4 大模型调用服务（OpenAI 兼容 /chat/completions 协议）。
 *
 * 配置项（application.yml，均可被同名环境变量覆盖）：
 *   deepseek.api-key      —— API Key，从环境变量 DEEPSEEK_API_KEY 注入，禁止硬编码
 *   deepseek.base-url      —— 默认 https://api.deepseek.com
 *   deepseek.model        —— 默认 deepseek-flash（V4.1-Flash，快且便宜）
 *   deepseek.timeout-seconds
 *
 * 未配置 Key 时 {@link #isEnabled()} 返回 false，调用方直接向用户返回明确错误（已无本地规则兜底）。
 */
@Service
public class DeepSeekService {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekService.class);

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-flash}")
    private String model;

    @Value("${deepseek.timeout-seconds:30}")
    private int timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient httpClient;

    /** 运行期配置服务：AI 配置中心可覆盖模型名（为空则用 yml 默认） */
    @org.springframework.beans.factory.annotation.Autowired
    private AiConfigService aiConfigService;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        logger.info("DeepSeek 服务初始化: model={}, enabled={}", getModel(), isEnabled());
    }

    /** 是否配置了可用的 API Key */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** 当前生效模型名：优先取 AI 配置中心的覆盖值，未配置则回退 yml */
    public String getModel() {
        if (aiConfigService != null) {
            String override = aiConfigService.get().getDeepseekModel();
            if (override != null && !override.trim().isEmpty()) return override;
        }
        return model;
    }

    /**
     * 非流式对话补全（关闭思考模式，首字响应快）。
     *
     * @param messagesList OpenAI 格式消息列表：[{role:system/user/assistant, content:String}, ...]
     * @return 模型回复正文
     * @throws DeepSeekException 网络错误、限流或业务错误时抛出，调用方需自行兜底
     */
    public String chat(List<Map<String, String>> messageList) {
        if (!isEnabled()) {
            throw new DeepSeekException("未配置 DEEPSEEK_API_KEY，DeepSeek 服务未启用");
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", getModel());
            body.put("stream", false);
            body.put("temperature", 0.7);
            body.put("max_tokens", 800);
            // V4 思考模式开关：disabled = 直接回答，降低延迟与费用
            body.putObject("thinking").put("type", "disabled");

            ArrayNode messages = body.putArray("messages");
            for (Map<String, String> m : messageList) {
                ObjectNode node = mapper.createObjectNode();
                node.put("role", m.get("role"));
                node.put("content", m.get("content"));
                messages.add(node);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String respBody = resp.body();

            if (resp.statusCode() != 200) {
                logger.warn("DeepSeek 调用失败 HTTP {}: {}", resp.statusCode(),
                        respBody == null ? "" : respBody.length() > 300 ? respBody.substring(0, 300) : respBody);
                throw new DeepSeekException("DeepSeek 接口返回 HTTP " + resp.statusCode());
            }

            JsonNode root = mapper.readTree(respBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().trim().isEmpty()) {
                JsonNode err = root.path("error");
                throw new DeepSeekException(err.isMissingNode() ? "DeepSeek 返回为空" : err.toString());
            }
            return contentNode.asText().trim();
        } catch (DeepSeekException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("DeepSeek 调用异常: {}", e.getMessage());
            throw new DeepSeekException("DeepSeek 调用异常: " + e.getMessage(), e);
        }
    }

    /** DeepSeek 调用异常（调用方向用户透传错误，不再有规则引擎兜底） */
    public static class DeepSeekException extends RuntimeException {
        public DeepSeekException(String message) { super(message); }
        public DeepSeekException(String message, Throwable cause) { super(message, cause); }
    }
}
