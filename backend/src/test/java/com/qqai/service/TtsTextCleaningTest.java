package com.qqai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTS 文本清洗测试。
 *
 * 背景（2026-10-19 线上问题）：聊天里点「朗读」时，消息原文是带 Markdown 结构、
 * HTML 实体和引号的摘要，清洗不彻底会让 TTS 断句很碎，线上日志里能看到
 * 「情况。，群氛围」「大量"草！给我杀！"」这类形态。
 *
 * 约定：交给 GPT-SoVITS 的文本必须是「可直接朗读的口语文本」——
 * 无引号、无实体、无列表符号、无「。，」这类连用标点。
 */
class TtsTextCleaningTest {

    private final GptSovitsService service = new GptSovitsService();

    @Test
    @DisplayName("引号与 HTML 实体必须去掉，不能把 &91; 读出来")
    void stripsQuotesAndEntities() {
        String out = service.cleanTextForTTS("&#91;TOC&#93; 他说\"你好\"，然后走了");
        assertFalse(out.contains("\""), "直引号应被去除: " + out);
        assertFalse(out.contains("&"), "实体应被解码或去除: " + out);
        assertTrue(out.contains("你好"), "正文必须保留: " + out);
    }

    @Test
    @DisplayName("「。，」这类连用标点必须收敛成「。」")
    void collapsesPunctuationRuns() {
        String out = service.cleanTextForTTS("互动情况。，群氛围活跃。，同时伴随调侃。");
        assertFalse(out.contains("。，"), "不应出现「。，」: " + out);
        assertFalse(out.contains("，，"), "不应出现连续逗号: " + out);
    }

    @Test
    @DisplayName("项目符号·与顿号转逗号，冒号转逗号")
    void normalizesSeparators() {
        String out = service.cleanTextForTTS("· 近期主题：电竞\n· 群氛围：活跃");
        assertFalse(out.contains("·"), "项目符号应被转换: " + out);
        assertFalse(out.contains("："), "冒号应转成逗号: " + out);
    }

    @Test
    @DisplayName("真实摘要文本：清洗后不含引号/实体/列表符，且保留正文")
    void cleansRealSummary() {
        String raw = "&#91;TOC&#93;\n群聊速览\n· 近期主题：群友们围绕电竞展开讨论。\n"
                + "· 群氛围：活跃，大量\"草！给我杀！\"等言语。\n"
                + "· 热点话题：\n1. \"TheShy\"选手表现\n2. \"伊洛\"教学方法";
        String out = service.cleanTextForTTS(raw);
        assertFalse(out.contains("\""), out);
        assertFalse(out.contains("&"), out);
        assertFalse(out.contains("·"), out);
        assertTrue(out.contains("群聊速览"), out);
        assertTrue(out.contains("TheShy"), "英文专有名词应保留: " + out);
        assertTrue(out.length() > 20, out);
    }
}
