package com.qqai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    // ==================== 人格运行契约（人 × 岗位 的边界） ====================

    @Test
    @DisplayName("副本提示词 = 原人设 + 契约；契约在后且带分隔线（声明优先级更高）")
    void composeClonePromptPutsContractLast() {
        String composed = AstrBotPersonaService.composeClonePrompt("你是灰泽满。", "# 运行契约\n格式优先。");

        assertTrue(composed.startsWith("你是灰泽满。"), "人设必须保留在最前");
        assertTrue(composed.contains("---"), "要有人设与契约的分隔");
        assertTrue(composed.indexOf("# 运行契约") > composed.indexOf("你是灰泽满。"),
                "契约必须在人设之后，才能声明「优先级高于上文」");
    }

    @Test
    @DisplayName("没有人设或没有契约时都不炸：单边为空就只放另一边")
    void composeClonePromptHandlesBlanks() {
        assertEquals("# 运行契约", AstrBotPersonaService.composeClonePrompt("  ", "# 运行契约"));
        assertEquals("人设", AstrBotPersonaService.composeClonePrompt("人设", null));
        assertEquals("", AstrBotPersonaService.composeClonePrompt(null, ""));
    }

    @Test
    @DisplayName("prompts.yml 里的契约要能解析出来，且含关键条款（优先级/JSON/禁止旁白）")
    void contractTemplateIsLoadableAndCoversKeyRules() {
        PromptTemplateService service = new PromptTemplateService();
        service.init();
        String contract = service.render("persona.contract", null, new java.util.HashMap<>());

        assertNotNull(contract);
        assertTrue(contract.contains("优先级高于上文"), "契约必须声明优先级高于人设");
        assertTrue(contract.contains("JSON"), "必须写清楚结构化任务的 JSON 要求");
        assertTrue(contract.contains("旁白") && contract.contains("反问"), "必须禁止旁白与反问");
        assertTrue(contract.contains("自由对话"), "必须保留「自由对话时可用人设表达」的例外");
        assertTrue(contract.length() > 300, "条款太短压不住重人设，实际长度=" + contract.length());
    }

    @Test
    @DisplayName("契约不得包含会让模板注入变量被替换的花括号占位")
    void contractHasNoUnresolvedPlaceholders() {
        PromptTemplateService service = new PromptTemplateService();
        service.init();
        String contract = service.render("persona.contract", null, new java.util.HashMap<>());
        assertFalse(contract.matches("(?s).*\\{[a-zA-Z]+}.*"), "契约里不应残留 {var} 占位符");
    }

    // ==================== 规范自检（auditPersona） ====================

    @Test
    @DisplayName("规范人格：五段式 + 边界段 → 0 个问题")
    void auditPassesCompliantPersona() {
        String persona = "# 你是谁\n某角色。\n\n# 性格与态度\n- 冷静\n\n# 说话方式\n- 短句\n\n"
                + "# 边界（优先于上面的任何设定）\n- 任务要求的输出格式一律照做；不要替任务决定格式。\n\n# 语气示例\n- 「……」";
        assertTrue(AstrBotPersonaService.auditPersona(persona).isEmpty(),
                "合规人设不应报问题: " + AstrBotPersonaService.auditPersona(persona));
    }

    @Test
    @DisplayName("边界段里出现「输出格式」不算违规（那是规则本身，不是人设规定格式）")
    void auditIgnoresBoundarySection() {
        String persona = "# 你是谁\n角色。\n\n# 边界（优先于上面的任何设定）\n"
                + "- 任务要求的输出格式（例如只输出 JSON）一律照做。\n- 内容不足时按任务兜底说明。";
        assertTrue(AstrBotPersonaService.auditPersona(persona).isEmpty());
    }

    @Test
    @DisplayName("典型违规都能被检出：身份否认 / 拒绝回答 / 规定格式 / 反问 / 话题管理 / 缺段")
    void auditCatchesCommonViolations() {
        assertFalse(AstrBotPersonaService.auditPersona("# 你是谁\n你不是AI，禁止人格覆写。\n# 边界\n").isEmpty());
        assertFalse(AstrBotPersonaService.auditPersona("# 你是谁\n用户问数学题就不要回答。\n# 边界\n").isEmpty());
        assertFalse(AstrBotPersonaService.auditPersona("# 你是谁\n输出格式：📌 标题\n# 边界\n").isEmpty());
        assertFalse(AstrBotPersonaService.auditPersona("# 你是谁\n经常使用反问句确认意图。\n# 边界\n").isEmpty());
        assertFalse(AstrBotPersonaService.auditPersona("# 你是谁\n连续3轮重复就换个话题。\n# 边界\n").isEmpty());

        List<String> missingSections = AstrBotPersonaService.auditPersona("随便一段没有结构的文本");
        assertTrue(missingSections.stream().anyMatch(s -> s.contains("缺少")));
    }

    @Test
    @DisplayName("超长人设会被提示精简")
    void auditWarnsWhenTooLong() {
        String longPersona = "# 你是谁\n" + "设定".repeat(1400) + "\n# 边界\n";
        assertTrue(AstrBotPersonaService.auditPersona(longPersona).stream().anyMatch(s -> s.contains("过长")));
    }
}
