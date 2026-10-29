package com.qqai.adapter.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.capability.HealthStatus;
import com.qqai.capability.chat.ChatProvider;
import com.qqai.capability.chat.ChatPurpose;
import com.qqai.capability.chat.ChatReply;
import com.qqai.capability.chat.ChatRequest;
import com.qqai.exception.BizException;
import com.qqai.service.AstrBotModelSelector;
import com.qqai.service.AstrBotPersonaService;
import com.qqai.service.AstrBotService;
import com.qqai.service.AstrBotSessionMemoryService;
import com.qqai.service.UserSettingsService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AstrBot 的 ChatProvider 适配器。
 *
 * <p>把原先散落在 AstrBotController 里的「请求组装 + 模型下发 + 人格/会话 + SSE 解析」
 * 收进一个边界内：业务层只发 ChatRequest、只拿 ChatReply。AstrBot 的 session/config_name/
 * selected_provider 等概念不允许穿过本类。</p>
 */
@Service
public class AstrBotChatAdapter implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(AstrBotChatAdapter.class);

    private final AstrBotPersonaService personaService;
    private final AstrBotModelSelector modelSelector;
    private final AstrBotMediaSupport mediaSupport;
    private final UserSettingsService userSettingsService;
    private final AstrBotSessionMemoryService sessionMemoryService;
    private final ObjectMapper objectMapper;

    @Value("${astrbot.api-url:http://localhost:6185}")
    private String astrBotApiUrl;

    @Value("${astrbot.token:}")
    private String astrBotToken;

    @Value("${astrbot.vision-config-name:vision}")
    private String visionConfigName;

    @Value("${astrbot.vision-task-config-name:vision-task}")
    private String visionTaskConfigName;

    private RestTemplate restTemplate;

    public AstrBotChatAdapter(AstrBotPersonaService personaService,
                              AstrBotModelSelector modelSelector,
                              AstrBotMediaSupport mediaSupport,
                              UserSettingsService userSettingsService,
                              AstrBotSessionMemoryService sessionMemoryService,
                              ObjectMapper objectMapper) {
        this.personaService = personaService;
        this.modelSelector = modelSelector;
        this.mediaSupport = mediaSupport;
        this.userSettingsService = userSettingsService;
        this.sessionMemoryService = sessionMemoryService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initHttpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(60000);
        factory.setReadTimeout(180000);
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        stringConverter.setWriteAcceptCharset(false);
        converters.add(stringConverter);
        converters.add(new MappingJackson2HttpMessageConverter());
        restTemplate = new RestTemplate(factory);
        restTemplate.setMessageConverters(converters);
    }

    @Override
    public ChatReply chat(ChatRequest request) {
        Map<String, Object> body = buildRequestBody(request);
        String sessionId = body.get("session_id") == null ? null : String.valueOf(body.get("session_id"));
        String token = resolveToken(request.userId());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                astrBotApiUrl + "/api/v1/chat", entity, String.class);

        maybeSetAnalysisTitle(request, sessionId);

        StringBuilder replyText = new StringBuilder();
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        String responseBody = response.getBody();
        if (responseBody != null) {
            log.debug("AstrBot 原始响应: {}", responseBody.substring(0, Math.min(500, responseBody.length())));
            for (String line : responseBody.split("\n")) {
                line = line.trim();
                if (!line.startsWith("data: ")) continue;
                try {
                    JsonNode json = objectMapper.readTree(line.substring(6));
                    String type = json.has("type") ? json.get("type").asText() : null;
                    if ("plain".equals(type)) {
                        String data = json.has("data") ? json.get("data").asText() : null;
                        if (data != null && !isToolResult(data)) {
                            replyText.append(data);
                        }
                    }
                    if (json.has("usage") && json.get("usage") != null) {
                        JsonNode usage = json.get("usage");
                        promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : promptTokens;
                        completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : completionTokens;
                        totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : totalTokens;
                    }
                } catch (Exception ignore) {
                    // 单行解析失败不影响后续
                }
            }
        }

        String finalReply = AstrBotService.sanitizeReply(replyText.toString().trim());
        if (finalReply.isEmpty()) {
            finalReply = request.purpose() == ChatPurpose.ANALYSIS
                    ? "抱歉，无法分析消息。"
                    : "抱歉，我没有理解您的问题。";
        }
        return new ChatReply(finalReply, promptTokens, completionTokens, totalTokens);
    }

    @Override
    public HealthStatus health() {
        try {
            java.net.URI uri = java.net.URI.create(astrBotApiUrl);
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(uri.getHost(),
                        uri.getPort() > 0 ? uri.getPort() : 80), 1000);
            }
            return new HealthStatus(true, "AstrBot " + astrBotApiUrl + " 可连接");
        } catch (Exception e) {
            return new HealthStatus(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 供单测：组装 AstrBot 请求体（不发 HTTP）。 */
    Map<String, Object> buildRequestBody(ChatRequest request) {
        boolean hasImage = request.hasImage();
        boolean analysis = request.purpose() == ChatPurpose.ANALYSIS;
        String token = resolveToken(request.userId());

        Map<String, Object> body = new HashMap<>();
        body.put("message", hasImage
                ? mediaSupport.buildMultimodalMessage(request.text(), request.imageUrls(), token)
                : request.text());
        body.put("username", analysis ? "analyzer" : (request.userQq() != null ? request.userQq() : "web_user"));
        body.put("enable_streaming", false);
        Map<String, Object> flags = new HashMap<>();
        flags.put("enable_streaming", false);
        flags.put("enable_reasoning", false);
        body.put("flags", flags);

        if (analysis) {
            buildAnalysisRequest(body, flags, request, hasImage);
        } else {
            buildChatRequest(body, flags, request, hasImage);
        }
        return body;
    }

    private void buildChatRequest(Map<String, Object> body, Map<String, Object> flags,
                                  ChatRequest request, boolean hasImage) {
        if (hasImage) {
            flags.put("enable_default_system_prompt", false);
        }
        String personaCfg = personaService.resolveConfigName(request.userId(), hasImage);
        if (personaCfg != null && !personaCfg.isBlank()) {
            body.put("config_name", personaCfg);
        } else if (request.configName() != null && !request.configName().isBlank()) {
            body.put("config_name", request.configName());
        }
        if (request.configId() != null && !request.configId().isBlank()) {
            body.put("config_id", request.configId());
        }

        String explicitModel = explicitModel(request.model());
        String activeModel = explicitModel != null ? explicitModel : defaultModel(request.userId());
        boolean profileApplied = (personaCfg != null && !personaCfg.isBlank())
                || (request.configName() != null && !request.configName().isBlank());
        if (activeModel != null && !activeModel.isBlank()) {
            body.put("model", activeModel);
            JsonNode selected = modelSelector.resolve(activeModel);
            boolean modelUnknown = selected == null;
            if (!modelUnknown && hasImage && profileApplied && !AstrBotModelSelector.supportsImage(selected)) {
                log.info("带图请求保留档案模型：所选 {} 不支持图片（profileApplied=true）", activeModel);
            } else if (!modelUnknown) {
                modelUnknown = !modelSelector.apply(body, activeModel);
            }
            if (modelUnknown && explicitModel != null && modelSelector.providers() != null) {
                throw new BizException(400, "所选模型「" + activeModel + "」已不在 AstrBot 配置中，请刷新模型列表后重新选择");
            }
        }

        String personaId = personaService.currentPersonaId(request.userId());
        body.put("session_id", AstrBotSessionMemoryService.buildSessionId(
                request.conversationId(), AstrBotSessionMemoryService.personaKey(personaId)));
    }

    private void buildAnalysisRequest(Map<String, Object> body, Map<String, Object> flags,
                                      ChatRequest request, boolean hasImage) {
        String cfg = personaService.resolveConfigName(request.userId(), hasImage);
        if (cfg == null && hasImage) {
            cfg = visionTaskConfigName != null && !visionTaskConfigName.isBlank()
                    ? visionTaskConfigName.trim() : visionConfigName;
        }
        if (cfg != null && !cfg.isBlank()) {
            body.put("config_name", cfg);
        }
        if (hasImage) {
            flags.put("enable_default_system_prompt", false);
        }
        // 分析永远是一次性会话（即使没有档案），便于标题/清理精确定位
        body.put("session_id", "qqai-" + UUID.randomUUID());

        String model = explicitModel(request.model());
        if (model == null) {
            model = defaultModel(request.userId());
        }
        if (model == null || model.isBlank()) {
            return;
        }
        body.put("model", model);
        JsonNode selected = modelSelector.resolve(model);
        if (selected == null) {
            log.warn("分析链路所选模型 {} 不在 AstrBot 配置中，本次交给档案默认模型", model);
            return;
        }
        if (hasImage && !AstrBotModelSelector.supportsImage(selected)) {
            log.info("分析链路带图但所选 {} 不支持图片，保留视觉档案模型", model);
            return;
        }
        modelSelector.apply(body, model);
    }

    /** 分析对话对用户可见：保留并异步补标题（写入带重试，供单测直接验证）。 */
    void maybeSetAnalysisTitle(ChatRequest request, String sessionId) {
        if (request.purpose() == ChatPurpose.ANALYSIS
                && request.title() != null && !request.title().isBlank() && sessionId != null) {
            sessionMemoryService.setTitleForSessionAsync(sessionId, request.title());
        }
    }

    private static String explicitModel(String model) {
        return model != null && !model.isBlank() && !"default".equalsIgnoreCase(model) ? model : null;
    }

    private String resolveToken(Long userId) {
        if (userId != null) {
            try {
                var settings = userSettingsService.findByUserId(String.valueOf(userId)).orElse(null);
                if (settings != null && settings.getAstrbotApiKey() != null && !settings.getAstrbotApiKey().isBlank()) {
                    return settings.getAstrbotApiKey();
                }
            } catch (Exception e) {
                log.warn("读取用户 AstrBot key 失败 userId={}: {}", userId, e.getMessage());
            }
        }
        return astrBotToken;
    }

    private String defaultModel(Long userId) {
        if (userId == null) return null;
        try {
            var settings = userSettingsService.findByUserId(String.valueOf(userId)).orElse(null);
            if (settings != null && settings.getLlmModel() != null && !settings.getLlmModel().isBlank()) {
                return settings.getLlmModel();
            }
        } catch (Exception e) {
            log.warn("读取用户默认模型失败 userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    private static boolean isToolResult(String data) {
        return (data.contains("\"id\"") && data.contains("\"ts\"") && data.contains("\"result\""))
                || (data.contains("\"id\"") && data.contains("\"name\"") && data.contains("\"parameters\""))
                || (data.startsWith("{") && data.contains("\"static\"") && data.contains("\"content\""));
    }
}
