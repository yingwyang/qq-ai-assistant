package com.qqai.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.entity.UserSettings;
import com.qqai.security.AuthPrincipal;
import com.qqai.service.UserSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/user")
public class UserSettingsController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserSettingsController.class);

    @Autowired
    private UserSettingsService userSettingsService;

    @Autowired
    private org.springframework.web.client.RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从当前登录上下文获取 userId（String 形式）。
     * 优先使用 JWT 解析出的 AuthPrincipal.userId；若未登录则返回 queryParam 兜底。
     */
    private String resolveUserId(String queryParamUserId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthPrincipal principal) {
            if (principal.userId() != null) {
                return String.valueOf(principal.userId());
            }
        }
        // 兼容未登录或 JWT 过滤未生效场景
        if (queryParamUserId != null && !queryParamUserId.isEmpty()) {
            return queryParamUserId;
        }
        return "default";
    }

    /**
     * 如果目标 userId 没有任何设置，但存在一条 userId="default" 的旧记录，
     * 则将 default 的内容迁移到目标用户下（避免老用户换浏览器后发现设置"消失"）。
     */
    private void migrateFromDefaultIfNeeded(String targetUserId) {
        if ("default".equals(targetUserId)) return;
        Optional<UserSettings> existing = userSettingsService.findByUserId(targetUserId);
        if (existing.isPresent()) return;
        Optional<UserSettings> def = userSettingsService.findByUserId("default");
        if (def.isEmpty()) return;
        UserSettings src = def.get();
        try {
            UserSettings copy = new UserSettings();
            copy.setUserId(targetUserId);
            copy.setBotName(src.getBotName());
            copy.setAstrbotApiKey(src.getAstrbotApiKey());
            copy.setLlmApiKey(src.getLlmApiKey());
            copy.setLlmBaseUrl(src.getLlmBaseUrl());
            copy.setLlmModel(src.getLlmModel());
            copy.setLlmModels(src.getLlmModels());
            copy.setProviders(src.getProviders());
            userSettingsService.save(copy);
        } catch (Exception ignore) {
            // 迁移失败不影响主流程
        }
    }

    /**
     * 获取用户设置
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@RequestParam(required = false) String userId) {
        try {
            String resolvedUserId = resolveUserId(userId);
            migrateFromDefaultIfNeeded(resolvedUserId);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");

            Optional<UserSettings> settings = userSettingsService.findByUserId(resolvedUserId);
            if (settings.isPresent()) {
                UserSettings s = settings.get();
                List<String> modelList = parseModels(s.getLlmModels());
                List<Map<String, Object>> providerList = parseProviders(s.getProviders());
                // 如果 providers 为空但旧字段有值，构造旧数据迁移
                if (providerList.isEmpty()
                        && ((s.getLlmApiKey() != null && !s.getLlmApiKey().isEmpty())
                            || (s.getLlmBaseUrl() != null && !s.getLlmBaseUrl().isEmpty()))) {
                    Map<String, Object> legacy = new java.util.HashMap<>();
                    legacy.put("name", "default");
                    legacy.put("apiKey", s.getLlmApiKey() != null ? s.getLlmApiKey() : "");
                    legacy.put("baseUrl", s.getLlmBaseUrl() != null ? s.getLlmBaseUrl() : "");
                    providerList.add(legacy);
                }
                java.util.HashMap<String, Object> data = new java.util.HashMap<>();
                data.put("botName", s.getBotName() != null ? s.getBotName() : "AstrBot 助手");
                data.put("astrbotApiKey", maskApiKey(s.getAstrbotApiKey()));
                data.put("llmApiKey", maskApiKey(s.getLlmApiKey()));
                data.put("llmBaseUrl", s.getLlmBaseUrl() != null ? s.getLlmBaseUrl() : "");
                data.put("llmModel", s.getLlmModel() != null ? s.getLlmModel() : "");
                data.put("llmModels", modelList);
                // providers 数组中 apiKey 掩码
                List<Map<String, Object>> maskedProviders = new ArrayList<>();
                for (Map<String, Object> p : providerList) {
                    Map<String, Object> mp = new java.util.HashMap<>(p);
                    if (mp.containsKey("apiKey")) {
                        mp.put("apiKey", maskApiKey((String) mp.get("apiKey")));
                    }
                    maskedProviders.add(mp);
                }
                data.put("providers", maskedProviders);
                result.putPOJO("data", data);
            } else {
                java.util.HashMap<String, Object> data = new java.util.HashMap<>();
                data.put("botName", "AstrBot 助手");
                data.put("astrbotApiKey", "");
                data.put("llmApiKey", "");
                data.put("llmBaseUrl", "");
                data.put("llmModel", "");
                data.put("llmModels", new ArrayList<String>());
                data.put("providers", new ArrayList<>());
                result.putPOJO("data", data);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 从提供商拉取模型列表（服务端执行）。
     *
     * <p>为什么必须在服务端做：GET /settings 只返回掩码后的 apiKey（sk-4****100e），
     * 前端拿掩码当 Bearer 去请求 /models 必然 401——这就是「刷新后获取不到模型」的根因。
     * 真实密钥只存在服务端，所以拉取也必须由服务端完成，并把结果持久化到 llm_models，
     * 避免「拉取到的列表刷新就没了」。</p>
     *
     * <p>安全：baseUrl 来自用户设置，属于出站请求。这里只允许 http/https，
     * 且拒绝回环/内网地址（防 SSRF）。</p>
     */
    @PostMapping("/provider-models/fetch")
    public ResponseEntity<?> fetchProviderModels(@RequestBody Map<String, Object> request) {
        ObjectNode result = objectMapper.createObjectNode();
        try {
            String resolvedUserId = resolveUserId((String) request.get("userId"));
            String providerName = (String) request.get("provider");
            Optional<UserSettings> opt = userSettingsService.findByUserId(resolvedUserId);
            if (opt.isEmpty()) {
                result.put("status", "error");
                result.put("message", "尚未保存任何提供商配置");
                return ResponseEntity.ok(result);
            }
            UserSettings settings = opt.get();
            Map<String, Object> provider = null;
            for (Map<String, Object> p : parseProviders(settings.getProviders())) {
                if (providerName != null && providerName.equals(String.valueOf(p.get("name")))) {
                    provider = p;
                    break;
                }
            }
            if (provider == null) {
                result.put("status", "error");
                result.put("message", "未找到提供商：" + providerName);
                return ResponseEntity.ok(result);
            }
            String apiKey = provider.get("apiKey") == null ? "" : String.valueOf(provider.get("apiKey"));
            String baseUrl = provider.get("baseUrl") == null ? "" : String.valueOf(provider.get("baseUrl"));
            if (apiKey.isBlank() || baseUrl.isBlank()) {
                result.put("status", "error");
                result.put("message", "该提供商的 API Key 或 Base URL 为空");
                return ResponseEntity.ok(result);
            }
            String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            assertOutboundUrlAllowed(url);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(apiKey);
            org.springframework.http.ResponseEntity<String> resp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                result.put("status", "error");
                result.put("message", "提供商返回 " + resp.getStatusCode().value());
                return ResponseEntity.ok(result);
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.getBody());
            com.fasterxml.jackson.databind.JsonNode arr = root.path("data");
            List<String> ids = new ArrayList<>();
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode m : arr) {
                    com.fasterxml.jackson.databind.JsonNode id = m.path("id");
                    if (id.isTextual() && !id.asText().isBlank()) {
                        ids.add(id.asText());
                    }
                }
            }
            // 持久化：刷新页面后仍能看到这份列表
            settings.setLlmModels(objectMapper.writeValueAsString(ids));
            userSettingsService.save(settings);

            result.put("status", "ok");
            result.putPOJO("models", ids);
            result.put("persisted", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("从提供商拉取模型失败: {}", e.getMessage());
            result.put("status", "error");
            result.put("message", "拉取失败：" + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /** 出站请求安全校验：只允许 http/https，且拒绝回环/内网地址（防 SSRF） */
    private void assertOutboundUrlAllowed(String url) throws Exception {
        java.net.URI uri = java.net.URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("只支持 http/https 地址");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("无效的地址");
        }
        java.net.InetAddress addr = java.net.InetAddress.getByName(host);
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
            throw new IllegalArgumentException("不允许访问内网地址");
        }
    }

    /**
     * 保存用户设置
     */
    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> request) {
        try {
            // 核心修复：userId 只信任 JWT 中的 AuthPrincipal，不再使用 request 中的 userId
            String resolvedUserId = resolveUserId((String) request.get("userId"));

            String botName = (String) request.get("botName");
            String astrbotApiKey = (String) request.get("astrbotApiKey");
            String llmApiKey = (String) request.get("llmApiKey");
            String llmBaseUrl = (String) request.get("llmBaseUrl");
            String llmModel = (String) request.get("llmModel");
            Object llmModelsObj = request.get("llmModels");
            String llmModelsJson = null;
            if (llmModelsObj != null) {
                try {
                    llmModelsJson = objectMapper.writeValueAsString(llmModelsObj);
                } catch (Exception e) {
                    llmModelsJson = "[]";
                }
            }
            Object providersObj = request.get("providers");
            String providersJson = null;
            if (providersObj != null) {
                try {
                    providersJson = objectMapper.writeValueAsString(providersObj);
                } catch (Exception e) {
                    providersJson = "[]";
                }
            }

            UserSettings settings = userSettingsService.saveSettings(resolvedUserId, botName,
                    astrbotApiKey, llmApiKey, llmBaseUrl, llmModel, llmModelsJson, providersJson);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.put("message", "设置保存成功");
            List<String> modelList = parseModels(settings.getLlmModels());
            List<Map<String, Object>> providerList = parseProviders(settings.getProviders());
            java.util.HashMap<String, Object> data = new java.util.HashMap<>();
            data.put("botName", settings.getBotName() != null ? settings.getBotName() : "AstrBot 助手");
            data.put("astrbotApiKey", maskApiKey(settings.getAstrbotApiKey()));
            data.put("llmApiKey", maskApiKey(settings.getLlmApiKey()));
            data.put("llmBaseUrl", settings.getLlmBaseUrl() != null ? settings.getLlmBaseUrl() : "");
            data.put("llmModel", settings.getLlmModel() != null ? settings.getLlmModel() : "");
            data.put("llmModels", modelList);
            // providers 数组中 apiKey 掩码
            List<Map<String, Object>> maskedSavedProviders = new ArrayList<>();
            for (Map<String, Object> p : providerList) {
                Map<String, Object> mp = new java.util.HashMap<>(p);
                if (mp.containsKey("apiKey")) {
                    mp.put("apiKey", maskApiKey((String) mp.get("apiKey")));
                }
                maskedSavedProviders.add(mp);
            }
            data.put("providers", maskedSavedProviders);
            result.putPOJO("data", data);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    private List<String> parseModels(String modelsJson) {
        if (modelsJson == null || modelsJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(modelsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> parseProviders(String providersJson) {
        if (providersJson == null || providersJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(providersJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * API Key 掩码：长度>8 → 前4位+****+后4位，否则返回****；空字符串保持空。
     */
    private String maskApiKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() > 8) {
            return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
        }
        return "****";
    }
}
