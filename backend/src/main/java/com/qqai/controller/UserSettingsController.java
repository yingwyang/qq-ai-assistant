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

    @Autowired
    private UserSettingsService userSettingsService;

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
