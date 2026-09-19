package com.qqai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 带图摘要（视觉链路）纯逻辑测试。
 *
 * <p>真实上传/调用 AstrBot 的部分在集成环境验证；这里只锁住提示词侧的关键约定：
 * 必须告知模型「图片已随附、可直接看图、不要说内容未知」，否则会退化成
 * 「中性 …（内容未知，未提供可辨内容）」这种无效摘要。</p>
 */
class AstrBotServiceVisionTest {

    @Test
    @DisplayName("带图提示词说明图片数量并要求直接描述画面")
    void hintTellsModelImagesAreAttached() {
        String hint = AstrBotService.imageAttachmentHint(2);

        assertTrue(hint.contains("2 张"), "应写明随附图片数量");
        assertTrue(hint.contains("不要再输出「内容未知」"), "应显式禁止占位式回答");
        assertTrue(hint.contains("JSON"), "应重申输出格式（结构化摘要仍是 JSON）");
    }

    @Test
    @DisplayName("带图提示词不改变原有内容（仅追加）")
    void hintIsAppendOnly() {
        String hint = AstrBotService.imageAttachmentHint(1);
        assertTrue(hint.startsWith("\n\n"), "应以换行开头，便于直接拼在提示词尾部");
    }

    @Test
    @DisplayName("GLM 视觉模型的 <|begin_of_box|> 包裹要清掉，否则结构化摘要解析失败")
    void stripsGlmSpecialTokens() {
        String raw = "<|begin_of_box|>{\"tags\": [\"图片分享\"], \"summary\": \"一张表情包\", "
                + "\"sentiment\": \"neutral\"}<|end_of_box|>";
        String cleaned = AstrBotService.sanitizeReply(raw);

        assertFalse(cleaned.contains("begin_of_box"));
        assertEquals("{\"tags\": [\"图片分享\"], \"summary\": \"一张表情包\", \"sentiment\": \"neutral\"}", cleaned);
    }

    @Test
    @DisplayName("```json 围栏只去围栏，JSON 本体不被「丢 JSON 行」规则拆坏")
    void keepsFencedJsonIntact() {
        String raw = "```json\n{\"tags\": [\"a\", \"b\"], \"summary\": \"ok\", \"sentiment\": \"neutral\"}\n```";
        assertEquals("{\"tags\": [\"a\", \"b\"], \"summary\": \"ok\", \"sentiment\": \"neutral\"}",
                AstrBotService.sanitizeReply(raw));
    }
}
