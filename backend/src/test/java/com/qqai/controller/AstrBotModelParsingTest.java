package com.qqai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AstrBot 模型列表解析测试。
 *
 * <p>背景：AstrBot 4.28 起 {@code /api/v1/models} 接口被移除（现在返回 404），
 * 模型列表必须从本地配置读：{@code cmd_config.json}，或者配置档案 {@code config/abconf_*.json}
 * （4.28 里每个档案是整份配置的快照，结构相同）。两者都走这个解析方法。</p>
 *
 * <p>顺带锁住容易踩的细节：显示名要去掉 provider 前缀与 {@code Pro/} 前缀，
 * {@code enabled} 取的是 {@code enable} 字段，视觉模型要带出 image 模态。</p>
 */
class AstrBotModelParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AstrBotController controller = new AstrBotController();

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    @DisplayName("解析 provider[]：id / 显示名 / 启用状态 / 来源 / 模态")
    void parsesProviderEntries() throws Exception {
        JsonNode root = json("""
                {
                  "provider": [
                    {
                      "id": "siliconflow/Pro/deepseek-ai/DeepSeek-V3.2",
                      "model": "Pro/deepseek-ai/DeepSeek-V3.2",
                      "enable": true,
                      "provider_source_id": "siliconflow",
                      "modalities": ["text"],
                      "max_context_tokens": 128000
                    },
                    {
                      "id": "siliconflow/zai-org/GLM-4.5V",
                      "model": "zai-org/GLM-4.5V",
                      "enable": false,
                      "provider_source_id": "siliconflow",
                      "modalities": ["text", "image"]
                    }
                  ]
                }
                """);

        ArrayNode models = controller.collectModelsFromConfig(root);
        assertEquals(2, models.size());

        JsonNode first = models.get(0);
        assertEquals("siliconflow/Pro/deepseek-ai/DeepSeek-V3.2", first.get("id").asText());
        // 显示名去掉 provider 前缀；Pro/ 属于模型名的一部分，保留
        assertEquals("DeepSeek-V3.2", first.get("name").asText());
        assertEquals("Pro/deepseek-ai/DeepSeek-V3.2", first.get("fullName").asText());
        assertTrue(first.get("enabled").asBoolean());
        assertEquals("siliconflow", first.get("provider").asText());
        assertEquals(128000L, first.get("maxContextTokens").asLong());

        JsonNode second = models.get(1);
        assertFalse(second.get("enabled").asBoolean());
        assertEquals(2, second.get("modalities").size());
        assertEquals("text", second.get("modalities").get(0).asText());
        assertEquals("image", second.get("modalities").get(1).asText(), "视觉模型要带出 image 模态");
    }

    @Test
    @DisplayName("没有 provider[] 时返回空数组（交给上层去别的来源找）")
    void emptyWhenProviderMissing() throws Exception {
        assertEquals(0, controller.collectModelsFromConfig(json("{\"config_version\": 2}")).size());
        assertEquals(0, controller.collectModelsFromConfig(json("{\"provider\": {}}")).size());
        assertEquals(0, controller.collectModelsFromConfig(null).size());
    }

    @Test
    @DisplayName("provider 项缺字段时用兜底值而不是抛异常")
    void toleratesMissingFields() throws Exception {
        JsonNode root = json("""
                { "provider": [ { "model": "gpt-4o" } ] }
                """);
        ArrayNode models = controller.collectModelsFromConfig(root);
        assertEquals(1, models.size());
        assertEquals("gpt-4o", models.get(0).get("id").asText(), "没有 id 时用 model 兜底");
        assertEquals("gpt-4o", models.get(0).get("name").asText());
        assertFalse(models.get(0).get("enabled").asBoolean(), "缺 enable 视为未启用");
        assertEquals("", models.get(0).get("provider").asText());
    }

    @Test
    @DisplayName("不含斜杠的模型名保持原样")
    void keepsPlainModelName() throws Exception {
        ArrayNode models = controller.collectModelsFromConfig(
                json("{ \"provider\": [ { \"id\": \"openai/gpt-4o\", \"model\": \"gpt-4o\", \"enable\": true } ] }"));
        assertEquals("gpt-4o", models.get(0).get("name").asText());
        assertEquals("gpt-4o", models.get(0).get("fullName").asText());
    }
}
