package com.qqai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双层提示词的确定性部分：人格 → 副本人格名 / 配置档案名的映射，以及
 * 「重人设压掉 JSON 格式」时的兜底判定。
 */
class AstrBotPersonaServiceTest {

    @Test
    @DisplayName("人格名 → 副本人格名：稳定、只含安全字符、不同人格不撞名")
    void clonePersonaIdIsStableAndSafe() {
        String a1 = AstrBotPersonaService.clonePersonaId("灰泽满");
        String a2 = AstrBotPersonaService.clonePersonaId("灰泽满");
        String b = AstrBotPersonaService.clonePersonaId("爱莉希雅");

        assertEquals(a1, a2, "同一人格每次都要映射到同一个副本");
        assertNotEquals(a1, b);
        assertTrue(a1.startsWith(AstrBotPersonaService.CLONE_PREFIX));
        assertTrue(a1.matches("[A-Za-z0-9_]+"), "副本名只能含安全字符，实际=" + a1);
    }

    @Test
    @DisplayName("人格名 → 档案名：文本/视觉两个档，且与人格一一对应")
    void profileNamePerModality() {
        String text = AstrBotPersonaService.profileName("qq_总结bot", false);
        String image = AstrBotPersonaService.profileName("qq_总结bot", true);

        assertTrue(text.startsWith(AstrBotPersonaService.PROFILE_PREFIX));
        assertTrue(text.endsWith("-text"));
        assertTrue(image.endsWith("-image"));
        assertNotEquals(text, image);
        assertTrue(text.matches("[A-Za-z0-9_-]+"), "档案名只能含安全字符，实际=" + text);
    }

    @Test
    @DisplayName("纯英文人格名保留可读部分（便于在 AstrBot 后台认出来）")
    void slugKeepsReadablePart() {
        assertTrue(AstrBotPersonaService.slug("Alice_bot-1").startsWith("alice_bot-1_"));
        assertTrue(AstrBotPersonaService.slug("").startsWith("p_"), "空名字也要产出合法标识");
    }

    @Test
    @DisplayName("人设输出不是 JSON 时要判定为需要中性档案重试")
    void needsNeutralRetryOnlyWhenPersonaUsed() {
        // 没用人格档案：不做兜底（保持原有行为）
        assertFalse(AstrBotService.needsNeutralRetry("随便说点什么", null));

        // 用人格但输出散文（重人设常见）→ 需要重试
        assertTrue(AstrBotService.needsNeutralRetry(
                "（正要点开果冻包装袋的手停了一下）啊，绿冻你好呀～", "qqai-p-x-text"));
        // 空响应 → 也要兜底
        assertTrue(AstrBotService.needsNeutralRetry("", "qqai-p-x-text"));
        // 正常 JSON → 不重试
        assertFalse(AstrBotService.needsNeutralRetry(
                "{\"tags\":[\"a\"],\"summary\":\"ok\",\"sentiment\":\"neutral\"}", "qqai-p-x-text"));
        // 带代码围栏的 JSON → 不重试
        assertFalse(AstrBotService.needsNeutralRetry(
                "```json\n{\"tags\":[],\"summary\":\"ok\",\"sentiment\":\"neutral\"}\n```", "qqai-p-x-text"));
    }
}
