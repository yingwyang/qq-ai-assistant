package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

/**
 * AstrBot 模型选择器：把本项目的「模型 id」翻译成 AstrBot 4.28 真正认的
 * {@code selected_provider} + {@code selected_model}。
 *
 * <p><b>为什么需要它</b>：AstrBot 4.28 的 {@code /api/v1/chat} 只读这两个字段，旧的 {@code model}
 * 会被<b>静默忽略</b>。忽略之后 AstrBot 退回「配置档案（profile）里的主模型」——本项目所有人格档案
 * 的主模型都是 SiliconFlow，于是出现「界面选了 DeepSeek、账单记在 SiliconFlow」；删掉 SiliconFlow 的
 * key 之后直接 401（SiliconFlow code 30014 Token is invalid）。</p>
 *
 * <p><b>数据来源</b>：AstrBot 全局配置 {@code <data-path>/cmd_config.json} 的 {@code provider[]}。
 * 每项的 {@code id} 是 provider 实例 id（形如 {@code deepseek/deepseek-flash}），{@code model}
 * 是发给上游的模型名（{@code deepseek-flash}），{@code modalities} 带 {@code image} 表示能看图。
 * provider 实例表（inst_map）也由这份配置构建，所以「配置里没有的模型」一定无法被选中。</p>
 *
 * <p>配置按文件 mtime + 长度缓存：聊天是热路径，不每条消息都读盘解析。</p>
 */
@Component
public class AstrBotModelSelector {

    private static final Logger log = LoggerFactory.getLogger(AstrBotModelSelector.class);

    private final ObjectMapper objectMapper;
    private final String dataPath;

    /** 缓存：只在 cmd_config.json 变更（mtime 或长度变化）时重新解析 */
    private long cachedMtime = Long.MIN_VALUE;
    private long cachedSize = -1L;
    private ArrayNode cachedProviders;

    public AstrBotModelSelector(ObjectMapper objectMapper,
                                @Value("${astrbot.data-path:}") String dataPath) {
        this.objectMapper = objectMapper;
        this.dataPath = dataPath;
    }

    /**
     * 读取全局配置里的 {@code provider[]}。
     *
     * @return 读得到时返回（可能为空的）数组；配置目录/文件不可读时返回 {@code null}
     *         —— 调用方据此区分「配置读不到」与「配置里确实没有这个模型」
     */
    public synchronized ArrayNode providers() {
        if (dataPath == null || dataPath.isBlank()) return null;
        File configFile = new File(dataPath, "cmd_config.json");
        if (!configFile.exists() || !configFile.canRead()) return null;
        long mtime = configFile.lastModified();
        long size = configFile.length();
        if (cachedProviders != null && mtime == cachedMtime && size == cachedSize) {
            return cachedProviders;
        }
        try {
            JsonNode root = objectMapper.readTree(configFile);
            ArrayNode providers = objectMapper.createArrayNode();
            JsonNode list = root == null ? null : root.get("provider");
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    providers.add(item);
                }
            }
            cachedProviders = providers;
            cachedMtime = mtime;
            cachedSize = size;
            return providers;
        } catch (Exception e) {
            log.warn("解析 AstrBot 配置失败（{}）: {}", configFile.getAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /**
     * 按模型 id 找 provider 配置项（远端平台名为 {@code AstrBot provider}）。
     *
     * @param modelId 形如 {@code deepseek/deepseek-flash}；null/空返回 null
     */
    public JsonNode find(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        ArrayNode providers = providers();
        if (providers == null) return null;
        for (JsonNode provider : providers) {
            if (provider.hasNonNull("id") && modelId.equals(provider.get("id").asText())) {
                return provider;
            }
        }
        return null;
    }

    /**
     * 按「上游模型名」找唯一 provider：既接受配置里的 {@code model}（如 {@code deepseek-flash}），
     * 也接受自定模型面板里用户手输的短名。
     *
     * <p>重名（多个 provider 用同一个模型名）时返回 null —— 猜错就会记到别的厂商账上，宁可不选。</p>
     */
    public JsonNode findByName(String name) {
        if (name == null || name.isBlank()) return null;
        ArrayNode providers = providers();
        if (providers == null) return null;
        JsonNode hit = null;
        for (JsonNode provider : providers) {
            if (name.equals(modelName(provider))) {
                if (hit != null) return null;
                hit = provider;
            }
        }
        return hit;
    }

    /**
     * 该 provider 是否声明了图片模态。带图请求只有选了能看图的模型才能顶掉视觉档案。
     */
    public static boolean supportsImage(JsonNode provider) {
        if (provider == null) return false;
        JsonNode modalities = provider.get("modalities");
        if (modalities == null || !modalities.isArray()) return false;
        for (JsonNode modality : modalities) {
            if ("image".equalsIgnoreCase(modality.asText())) return true;
        }
        return false;
    }

    /**
     * 取发给上游的模型名：{@code model} 字段优先，缺失时回落 id 的最后一段。
     */
    public static String modelName(JsonNode provider) {
        if (provider == null) return null;
        if (provider.hasNonNull("model")) {
            String name = provider.get("model").asText().trim();
            if (!name.isEmpty()) return name;
        }
        String id = provider.hasNonNull("id") ? provider.get("id").asText() : "";
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    /**
     * 解析选中的模型：先按 provider 实例 id 精确匹配，再按上游模型名反查（自定义模型面板允许手输短名）。
     */
    public JsonNode resolve(String modelId) {
        JsonNode provider = find(modelId);
        return provider != null ? provider : findByName(modelId);
    }

    /**
     * 把模型选择写进 {@link Map} 形态的请求体。
     *
     * @return true 表示已写入 {@code selected_provider}/{@code selected_model}；false 表示模型不在配置里
     */
    public boolean apply(Map<String, Object> body, String modelId) {
        if (body == null) return false;
        JsonNode provider = resolve(modelId);
        if (provider == null) return false;
        body.put("selected_provider", provider.get("id").asText());
        String name = modelName(provider);
        if (name != null && !name.isBlank()) {
            body.put("selected_model", name);
        }
        return true;
    }

    /**
     * 把模型选择写进 {@link ObjectNode} 形态的请求体（AstrBotService 的摘要链路用）。
     */
    public boolean apply(ObjectNode body, String modelId) {
        if (body == null) return false;
        JsonNode provider = resolve(modelId);
        if (provider == null) return false;
        body.put("selected_provider", provider.get("id").asText());
        String name = modelName(provider);
        if (name != null && !name.isBlank()) {
            body.put("selected_model", name);
        }
        return true;
    }
}
