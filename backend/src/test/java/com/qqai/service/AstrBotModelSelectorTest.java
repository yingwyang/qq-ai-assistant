package com.qqai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AstrBot 模型选择器测试。
 *
 * <p>锁住的是这条线上最容易静默失效的契约：AstrBot 4.28 只认
 * {@code selected_provider}/{@code selected_model}，只传 {@code model} 会被忽略、
 * 回落到配置档案的主模型（本项目所有人格档案的主模型都是 SiliconFlow）。</p>
 */
class AstrBotModelSelectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String CONFIG = """
            {
              "provider": [
                {
                  "id": "deepseek/deepseek-flash",
                  "model": "deepseek-flash",
                  "enable": true,
                  "provider_source_id": "deepseek",
                  "modalities": ["text", "image"]
                },
                {
                  "id": "siliconflow/Pro/deepseek-ai/DeepSeek-V3.2",
                  "model": "Pro/deepseek-ai/DeepSeek-V3.2",
                  "enable": true,
                  "provider_source_id": "siliconflow",
                  "modalities": ["text"]
                }
              ]
            }
            """;

    private AstrBotModelSelector selector(Path dataDir) {
        return new AstrBotModelSelector(mapper, dataDir.toString());
    }

    @Test
    @DisplayName("配置不可读时返回 null，调用方据此区分「读不到」与「确实没有」")
    void providersNullWhenUnreadable(@TempDir Path dir) {
        assertNull(new AstrBotModelSelector(mapper, "").providers());
        assertNull(selector(dir).providers());
        assertNull(selector(dir).find("deepseek/deepseek-flash"));
    }

    @Test
    @DisplayName("按 id 找到 provider，并翻译成 selected_provider + selected_model")
    void appliesSelection(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("cmd_config.json"), CONFIG, StandardCharsets.UTF_8);
        AstrBotModelSelector selector = selector(dir);

        Map<String, Object> body = new HashMap<>();
        assertTrue(selector.apply(body, "deepseek/deepseek-flash"));
        // provider 实例 id 原样下发，模型名用配置里的 model 字段
        assertEquals("deepseek/deepseek-flash", body.get("selected_provider"));
        assertEquals("deepseek-flash", body.get("selected_model"));

        ObjectNode node = mapper.createObjectNode();
        assertTrue(selector.apply(node, "siliconflow/Pro/deepseek-ai/DeepSeek-V3.2"));
        assertEquals("Pro/deepseek-ai/DeepSeek-V3.2", node.get("selected_model").asText());
    }

    @Test
    @DisplayName("配置里没有的模型不下发，也不会写坏请求体")
    void skipsUnknownModel(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("cmd_config.json"), CONFIG, StandardCharsets.UTF_8);
        AstrBotModelSelector selector = selector(dir);

        Map<String, Object> body = new HashMap<>();
        assertFalse(selector.apply(body, "deepseek/not-exist"));
        assertTrue(body.isEmpty());
        assertNull(selector.find(null));
        assertNull(selector.find(""));
    }

    @Test
    @DisplayName("图片模态决定带图请求能不能顶掉视觉档案")
    void detectsImageModality(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("cmd_config.json"), CONFIG, StandardCharsets.UTF_8);
        AstrBotModelSelector selector = selector(dir);

        assertTrue(AstrBotModelSelector.supportsImage(selector.find("deepseek/deepseek-flash")));
        assertFalse(AstrBotModelSelector.supportsImage(selector.find("siliconflow/Pro/deepseek-ai/DeepSeek-V3.2")));
        assertFalse(AstrBotModelSelector.supportsImage(null));
    }

    @Test
    @DisplayName("model 字段缺失时用 id 最后一段兜底")
    void modelNameFallsBackToIdTail(@TempDir Path dir) throws Exception {
        String noModel = """
                {"provider": [{"id": "deepseek/deepseek-v4-pro", "enable": true, "modalities": ["text"]}]}
                """;
        Files.writeString(dir.resolve("cmd_config.json"), noModel, StandardCharsets.UTF_8);

        assertEquals("deepseek-v4-pro", AstrBotModelSelector.modelName(selector(dir).find("deepseek/deepseek-v4-pro")));
        assertNull(AstrBotModelSelector.modelName(null));
    }

    @Test
    @DisplayName("按上游模型名反查：唯一才认，重名不猜（避免记到别的厂商账上）")
    void findsByModelName(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("cmd_config.json"), CONFIG, StandardCharsets.UTF_8);
        AstrBotModelSelector selector = selector(dir);

        // 短名 deepseek-flash 能反查到 deepseek/deepseek-flash
        assertEquals("deepseek/deepseek-flash",
                selector.findByName("deepseek-flash").get("id").asText());
        // Pro/ 前缀属于模型名本身，要原样匹配
        assertEquals("siliconflow/Pro/deepseek-ai/DeepSeek-V3.2",
                selector.findByName("Pro/deepseek-ai/DeepSeek-V3.2").get("id").asText());
        assertNull(selector.findByName("not-a-model"));
        assertNull(selector.findByName(""));

        // apply 必须与 resolve 同口径：手输短名也要能写进请求体
        Map<String, Object> body = new HashMap<>();
        assertTrue(selector.apply(body, "deepseek-flash"));
        assertEquals("deepseek/deepseek-flash", body.get("selected_provider"));
        assertEquals("deepseek-flash", body.get("selected_model"));

        // 两个 provider 用同一个上游模型名 → 不猜
        String dup = """
                {"provider": [
                  {"id": "a/same-model", "model": "same-model", "enable": true},
                  {"id": "b/same-model", "model": "same-model", "enable": true}
                ]}
                """;
        Files.writeString(dir.resolve("cmd_config.json"), dup, StandardCharsets.UTF_8);
        dir.resolve("cmd_config.json").toFile().setLastModified(System.currentTimeMillis() + 2000);
        assertNull(selector.findByName("same-model"));
    }

    @Test
    @DisplayName("配置改动后重新读取：不因缓存继续用旧模型表")
    void reloadsWhenConfigChanges(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cmd_config.json");
        Files.writeString(file, CONFIG, StandardCharsets.UTF_8);
        AstrBotModelSelector selector = selector(dir);
        assertNull(selector.find("deepseek/deepseek-v4-pro"));

        String updated = """
                {"provider": [{"id": "deepseek/deepseek-v4-pro", "model": "deepseek-v4-pro", "enable": true, "modalities": ["text", "image"]}]}
                """;
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        // 文件系统 mtime 精度可能到秒：显式把修改时间往前推，确保缓存判定能失效
        file.toFile().setLastModified(System.currentTimeMillis() + 2000);

        assertNotNull(selector.find("deepseek/deepseek-v4-pro"));
        assertNull(selector.find("deepseek/deepseek-flash"));
    }
}
