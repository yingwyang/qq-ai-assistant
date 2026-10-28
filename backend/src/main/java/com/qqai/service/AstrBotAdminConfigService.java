package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理后台「AstrBot 配置」服务：读写 AstrBot 的提供商（provider_sources）、模型（provider[]）、
 * 配置档案（config-profiles），并维护本系统的摘要模型。
 *
 * <p><b>为什么走 AstrBot 自己的开放 API 而不是改 cmd_config.json</b>：AstrBot 4.28 的
 * {@code /api/v1/provider-sources}、{@code /api/v1/providers}、{@code /api/v1/config-profiles}
 * 用我们现成的 API Key 即可调用（实测 200），并且服务端会**热重载**配置；
 * 直接改文件既不会触发热重载，还会被 AstrBot 下次保存覆盖。实测这些接口都不需要 WebUI 的 JWT。</p>
 *
 * <p><b>密钥安全</b>：AstrBot 的列表接口会返回明文 key。本服务只向前端返回
 * {@link #maskKey(String)} 后的缩写（如 {@code sk-epzs…huf}）；编辑时 key 留空表示"不修改"，
 * 避免把掩码值写回配置（历史上就有"把掩码当 Bearer 用"导致 401 的坑）。</p>
 *
 * <p><b>配置档案的快照问题</b>：4.28 每份 {@code abconf_*} 都是整份配置的副本。旧档案里可能
 * 只有 SiliconFlow、或是没有 provider[]。{@link #syncProfiles} 把全局的 provider_sources/provider[]
 * 整套写进每份档案，并可统一改写主模型，先 dry-run 再 apply。</p>
 */
@Service
public class AstrBotAdminConfigService {

    private static final Logger log = LoggerFactory.getLogger(AstrBotAdminConfigService.class);

    /** 摘要模型对应的配置键（ConfigService 运行时配置，改完立即生效，见 AstrBotService#doChat） */
    public static final String SUMMARY_MODEL_KEY = "astrbot.summary-model";

    @Value("${astrbot.api-url}")
    private String astrBotApiUrl;

    @Value("${astrbot.token}")
    private String astrBotToken;

    /** 系统底座档案：删除它们会让人格/视觉链路缺档，先挡住 */
    @Value("${astrbot.vision-config-name:vision}")
    private String visionConfigName;

    @Value("${astrbot.vision-task-config-name:vision-task}")
    private String visionTaskConfigName;

    @Value("${astrbot.persona-text-base-profile:ray}")
    private String personaTextBaseProfile;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigService configService;
    private final Environment environment;

    public AstrBotAdminConfigService(RestTemplate restTemplate,
                                     ObjectMapper objectMapper,
                                     ConfigService configService,
                                     Environment environment) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.environment = environment;
    }

    // ======================================================================
    // 读：总览
    // ======================================================================

    /**
     * 管理页一次性拉取的清单：摘要模型 + 提供商 + 模型 + 配置档案。
     */
    public Map<String, Object> inventory() {
        JsonNode sources = listProviderSources();
        JsonNode providers = listProviders();
        JsonNode profiles = listConfigProfiles();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summaryView(providers));
        result.put("sources", mapSources(sources, providers));
        result.put("providers", mapProviders(providers, sources));
        result.put("profiles", mapProfiles(profiles));
        return result;
    }

    /** 摘要模型视图：当前值 + 候选项（来自 AstrBot 已启用的模型） */
    public Map<String, Object> summaryView(JsonNode providers) {
        String configured = configService.getEffectiveValue(SUMMARY_MODEL_KEY);
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (providers != null && providers.isArray()) {
            for (JsonNode provider : providers) {
                if (!provider.path("enable").asBoolean(false)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", provider.path("id").asText());
                item.put("label", provider.path("model").asText(provider.path("id").asText()));
                item.put("sourceId", provider.path("provider_source_id").asText(""));
                item.put("enabled", true);
                item.put("modalities", textList(provider.path("modalities")));
                candidates.add(item);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", configured.isBlank() ? null : configured);
        summary.put("effective", configured.isBlank() ? null : configured);
        summary.put("candidates", candidates);
        return summary;
    }

    /**
     * 设置摘要模型（写 ConfigService 运行时覆盖，立即生效、无需重启）。
     *
     * @param modelId 形如 {@code deepseek/deepseek-flash}；空字符串表示清除（交给档案默认模型）
     */
    public Map<String, Object> updateSummaryModel(String modelId) {
        String model = modelId == null ? "" : modelId.trim();
        if (!model.isEmpty()) {
            JsonNode providers = listProviders();
            boolean exists = false;
            for (JsonNode provider : providers) {
                if (model.equals(provider.path("id").asText())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                throw new BizException(400, "模型不存在或未在 AstrBot 配置中：" + model);
            }
        }
        configService.updateConfig(Map.of(SUMMARY_MODEL_KEY, model));
        log.info("摘要模型已更新为 {}", model.isEmpty() ? "(档案默认)" : model);
        return summaryView(listProviders());
    }

    // ======================================================================
    // 写：提供商 provider_sources
    // ======================================================================

    /** 新增提供商（id/type/api_base/key/enable/timeout） */
    public void createSource(Map<String, Object> body) {
        String id = text(body.get("id"));
        String apiBase = text(body.get("apiBase"));
        if (id.isEmpty()) throw new BizException(400, "提供商 ID 不能为空");
        if (apiBase.isEmpty()) throw new BizException(400, "Base URL 不能为空");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("type", text(body.get("type")).isEmpty() ? "openai_chat_completion" : text(body.get("type")));
        payload.put("api_base", apiBase);
        String apiKey = text(body.get("apiKey"));
        payload.put("key", apiKey.isEmpty() ? List.of() : List.of(apiKey));
        payload.put("enable", body.get("enable") == null || Boolean.parseBoolean(String.valueOf(body.get("enable"))));
        payload.put("timeout", body.get("timeout") == null ? 120 : Integer.parseInt(String.valueOf(body.get("timeout"))));
        call(HttpMethod.POST, "/api/v1/provider-sources", payload);
    }

    /** 修改提供商：key 留空 = 不修改（前端拿到的是掩码，不能回写） */
    public void updateSource(Map<String, Object> body) {
        String id = text(body.get("id"));
        if (id.isEmpty()) throw new BizException(400, "提供商 ID 不能为空");
        ObjectNode current = (ObjectNode) fetchProviderSource(id);

        if (body.get("apiBase") != null && !text(body.get("apiBase")).isEmpty()) {
            current.put("api_base", text(body.get("apiBase")));
        }
        String apiKey = text(body.get("apiKey"));
        if (!apiKey.isEmpty()) {
            ArrayNode keys = objectMapper.createArrayNode();
            keys.add(apiKey);
            current.set("key", keys);
        }
        if (body.get("enable") != null) current.put("enable", Boolean.parseBoolean(String.valueOf(body.get("enable"))));
        if (body.get("timeout") != null) current.put("timeout", Integer.parseInt(String.valueOf(body.get("timeout"))));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_id", id);
        payload.putAll(objectMapper.convertValue(current, Map.class));
        call(HttpMethod.PUT, "/api/v1/provider-sources/by-id", payload);
    }

    /** 删除提供商：仍被模型引用时拒绝（避免一删就把所有模型打挂） */
    public void deleteSource(String id) {
        if (id == null || id.isBlank()) throw new BizException(400, "提供商 ID 不能为空");
        JsonNode providers = listProviders();
        long used = 0;
        for (JsonNode provider : providers) {
            String sourceOfProvider = providerSourceId(provider);
            if (id.equals(sourceOfProvider)) used++;
        }
        if (used > 0) {
            throw new BizException(400, "该提供商下还有 " + used + " 个模型，请先删除这些模型");
        }
        call(HttpMethod.DELETE, "/api/v1/provider-sources/by-id?source_id=" + encode(id), null);
    }

    // ======================================================================
    // 写：模型 provider[]
    // ======================================================================

    /** 新增模型：id 由 sourceId/model 组成（与 AstrBot 列表一致） */
    public void createProvider(Map<String, Object> body) {
        String sourceId = text(body.get("sourceId"));
        String model = text(body.get("model"));
        if (sourceId.isEmpty()) throw new BizException(400, "请选择所属提供商");
        if (model.isEmpty()) throw new BizException(400, "模型名不能为空");

        Map<String, Object> payload = new LinkedHashMap<>();
        // 注意：POST /providers 只认 provider_source_id（source_id 会被 to_dashboard_config 丢掉，
        // 结果是新建的模型没有来源、按来源删除/统计全对不上）
        payload.put("provider_source_id", sourceId);
        payload.put("id", sourceId + "/" + model);
        payload.put("model", model);
        payload.put("type", "openai_chat_completion");
        payload.put("enable", body.get("enable") == null || Boolean.parseBoolean(String.valueOf(body.get("enable"))));
        if (body.get("modalities") instanceof List<?> list && !list.isEmpty()) {
            payload.put("modalities", list);
        }
        call(HttpMethod.POST, "/api/v1/providers", payload);
    }

    /** 启停模型（AstrBot 侧实时生效） */
    public void setProviderEnabled(String id, boolean enabled) {
        if (id == null || id.isBlank()) throw new BizException(400, "模型 ID 不能为空");
        Map<String, Object> payload = Map.of("enabled", enabled);
        call(HttpMethod.PATCH, "/api/v1/providers/" + encodePath(id) + "/enabled", payload);
    }

    /** 删除模型 */
    public void deleteProvider(String id) {
        if (id == null || id.isBlank()) throw new BizException(400, "模型 ID 不能为空");
        call(HttpMethod.DELETE, "/api/v1/providers/by-id?provider_id=" + encode(id), null);
    }

    // ======================================================================
    // 写：配置档案
    // ======================================================================

    /**
     * 把全局 provider_sources + provider[] 同步进每份配置档案（先 dry-run 再 apply）。
     *
     * <p>4.28 的档案是整份配置快照：旧档案里可能只有 SiliconFlow 或根本没有 provider[]，
     * 一旦某人删掉旧 key 就会在档案驱动路径上报 401。同步后档案与全局一致；
     * 可选把每份档案的主模型统一改成某个模型（例如 {@code deepseek/deepseek-flash}）。</p>
     *
     * @param apply        false = 只返回将发生的变化，不写
     * @param mainModelId  可选：统一改写档案主模型；blank = 保持原样
     */
    public Map<String, Object> syncProfiles(boolean apply, String mainModelId) {
        JsonNode sources = listProviderSources();
        JsonNode providers = listProviders();
        JsonNode profiles = listConfigProfiles();
        String targetModel = mainModelId == null ? "" : mainModelId.trim();

        List<Map<String, Object>> items = new ArrayList<>();
        int changed = 0;
        for (JsonNode profile : profiles) {
            String id = profile.path("id").asText();
            String name = profile.path("name").asText();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", name);
            try {
                JsonNode data = call(HttpMethod.GET, "/api/v1/config-profiles/" + encode(id), null).path("data");
                JsonNode configNode = data.path("config");
                if (!configNode.isObject()) {
                    item.put("skipped", "档案配置不可读");
                    items.add(item);
                    continue;
                }
                ObjectNode config = (ObjectNode) configNode.deepCopy();
                String beforeMain = config.path("agent_runner").path("config").path("model").path("provider_id").asText("");
                int beforeProviders = config.path("provider").size();
                int beforeSources = config.path("provider_sources").size();

                config.set("provider_sources", sources.deepCopy());
                config.set("provider", providers.deepCopy());
                String afterMain = beforeMain;
                if (!targetModel.isEmpty()) {
                    ObjectNode agentRunner = config.with("agent_runner");
                    ObjectNode agentConfig = agentRunner.with("config");
                    agentConfig.with("model").put("provider_id", targetModel);
                    afterMain = targetModel;
                }
                boolean dirty = beforeProviders != providers.size()
                        || beforeSources != sources.size()
                        || !beforeMain.equals(afterMain);
                item.put("mainModelBefore", beforeMain);
                item.put("mainModelAfter", afterMain);
                item.put("providerCountBefore", beforeProviders);
                item.put("providerCountAfter", providers.size());
                item.put("sourceCountBefore", beforeSources);
                item.put("sourceCountAfter", sources.size());
                item.put("changed", dirty);
                if (dirty) changed++;
                if (apply && dirty) {
                    call(HttpMethod.PUT, "/api/v1/config-profiles/" + encode(id), config);
                    item.put("applied", true);
                }
                items.add(item);
            } catch (Exception e) {
                item.put("error", e.getMessage());
                items.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", apply);
        result.put("changedCount", changed);
        result.put("items", items);
        return result;
    }

    /** 删除配置档案（系统底座档案先挡住） */
    public void deleteProfile(String id) {
        if (id == null || id.isBlank()) throw new BizException(400, "档案 ID 不能为空");
        JsonNode profiles = listConfigProfiles();
        String name = null;
        for (JsonNode profile : profiles) {
            if (id.equals(profile.path("id").asText())) {
                name = profile.path("name").asText();
                break;
            }
        }
        if (name == null) throw new BizException(404, "档案不存在：" + id);
        Set<String> protectedNames = Set.of(
                visionConfigName == null ? "vision" : visionConfigName.trim(),
                visionTaskConfigName == null ? "vision-task" : visionTaskConfigName.trim(),
                personaTextBaseProfile == null ? "ray" : personaTextBaseProfile.trim());
        if (protectedNames.contains(name)) {
            throw new BizException(400, "「" + name + "」是系统底座档案（人格/视觉链路在用），不能删除");
        }
        call(HttpMethod.DELETE, "/api/v1/config-profiles/" + encode(id), null);
    }

    // ======================================================================
    // 内部：HTTP 调用与映射
    // ======================================================================

    private JsonNode call(HttpMethod method, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", astrBotToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            // 用 URI 而不是拼接后的 String：String 形式会被 RestTemplate 再编码一次，
            // 查询参数里的 %2F 会变成 %252F，AstrBot 收到不存在的 id 却仍回 "删除成功"（静默不删）。
            java.net.URI uri = java.net.URI.create(astrBotApiUrl + path);
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, method, new HttpEntity<>(body, headers), String.class);
            String text = response.getBody();
            JsonNode root = text == null || text.isBlank() ? objectMapper.createObjectNode()
                    : objectMapper.readTree(text);
            if (root.has("status") && !"ok".equals(root.path("status").asText())) {
                throw new BizException(502, "AstrBot 返回失败：" + root.path("message").asText("未知错误"));
            }
            return root;
        } catch (BizException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            String detail = e.getResponseBodyAsString();
            try {
                JsonNode err = objectMapper.readTree(detail);
                detail = err.path("message").asText(err.path("detail").asText(detail));
            } catch (Exception ignored) {
                // 保留原始响应体
            }
            throw new BizException(502, "AstrBot 接口失败（HTTP " + e.getStatusCode().value() + "）：" + detail);
        } catch (Exception e) {
            throw new BizException(502, "AstrBot 接口不可用：" + e.getMessage());
        }
    }

    private JsonNode listProviderSources() {
        return call(HttpMethod.GET, "/api/v1/provider-sources", null).path("data").path("provider_sources");
    }

    private JsonNode listProviders() {
        return call(HttpMethod.GET, "/api/v1/providers", null).path("data").path("providers");
    }

    private JsonNode listConfigProfiles() {
        return call(HttpMethod.GET, "/api/v1/config-profiles", null).path("data").path("info_list");
    }

    private JsonNode fetchProviderSource(String id) {
        JsonNode data = call(HttpMethod.GET, "/api/v1/provider-sources/by-id?source_id=" + encode(id), null).path("data");
        if (!data.isObject()) throw new BizException(404, "提供商不存在：" + id);
        return data;
    }

    private List<Map<String, Object>> mapSources(JsonNode sources, JsonNode providers) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode source : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            String id = source.path("id").asText();
            item.put("id", id);
            item.put("type", source.path("type").asText(""));
            item.put("apiBase", source.path("api_base").asText(""));
            item.put("enable", source.path("enable").asBoolean(true));
            item.put("timeout", source.path("timeout").asInt(120));
            String rawKey = firstKey(source.path("key"));
            item.put("keySet", !rawKey.isBlank());
            item.put("keyMasked", maskKey(rawKey));
            int count = 0;
            for (JsonNode provider : providers) {
                if (id.equals(providerSourceId(provider))) count++;
            }
            item.put("providerCount", count);
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapProviders(JsonNode providers, JsonNode sources) {
        Map<String, String> maskedBySource = new LinkedHashMap<>();
        for (JsonNode source : sources) {
            maskedBySource.put(source.path("id").asText(), maskKey(firstKey(source.path("key"))));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode provider : providers) {
            String sourceId = providerSourceId(provider);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", provider.path("id").asText());
            item.put("sourceId", sourceId);
            item.put("model", provider.path("model").asText(""));
            item.put("enable", provider.path("enable").asBoolean(true));
            item.put("modalities", textList(provider.path("modalities")));
            item.put("maxContextTokens", provider.path("max_context_tokens").asLong(0));
            String directKey = firstKey(provider.path("key"));
            item.put("keyMasked", directKey.isBlank() ? maskedBySource.getOrDefault(sourceId, "") : maskKey(directKey));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapProfiles(JsonNode profiles) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode profile : profiles) {
            String id = profile.path("id").asText();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", profile.path("name").asText(""));
            try {
                JsonNode config = call(HttpMethod.GET, "/api/v1/config-profiles/" + encode(id), null)
                        .path("data").path("config");
                item.put("mainModelId", config.path("agent_runner").path("config").path("model").path("provider_id").asText(""));
                item.put("providerCount", config.path("provider").size());
                item.put("sourceCount", config.path("provider_sources").size());
                item.put("personaId", config.path("agent_runner").path("config").path("persona").path("persona_id").asText(""));
            } catch (Exception e) {
                item.put("error", e.getMessage());
            }
            list.add(item);
        }
        return list;
    }

    private static List<String> textList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) list.add(item.asText());
        }
        return list;
    }

    /**
     * 模型归属的提供商 id：优先 provider_source_id；旧/手工创建的条目可能没有这个字段，
     * 此时用模型 id 的第一段（AstrBot 的 id 约定就是 {@code sourceId/model}）。
     */
    private static String providerSourceId(JsonNode provider) {
        String declared = provider.path("provider_source_id").asText("");
        if (!declared.isBlank()) return declared;
        String id = provider.path("id").asText("");
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : "";
    }

    private static String firstKey(JsonNode keyNode) {
        if (keyNode == null) return "";
        if (keyNode.isArray()) {
            for (JsonNode item : keyNode) {
                String value = item.asText("");
                if (!value.isBlank()) return value;
            }
            return "";
        }
        return keyNode.asText("");
    }

    /** 密钥缩写：只露前 6 位与后 4 位，中间用省略号；太短则整体打码 */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        String value = key.trim();
        if (value.length() <= 10) return "****";
        return value.substring(0, 6) + "…" + value.substring(value.length() - 4);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return value.replace(" ", "%20");
    }
}
