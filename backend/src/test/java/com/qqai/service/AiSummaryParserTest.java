package com.qqai.service;

import com.qqai.entity.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摘要解析健壮性测试：模型输出里夹带工具调用 JSON 时，仍要能解析出真正的答案。
 */
class AiSummaryParserTest {

    private final AiSummaryParser parser = new AiSummaryParser();

    @Test
    @DisplayName("从「工具调用 JSON + 真正答案 JSON」中取最后一个对象")
    void shouldExtractLastJsonObject() {
        String raw = "{\"id\": \"01a0b7e819fd6b59\", \"name\": \"future_task\", \"args\": {}, \"ts\": 1789791835.3}"
                + "{\"id\": \"01a0b7e819fd6b59\", \"ts\": 1789791835.3, \"result\": \"error: action must be one of create\"}"
                + "{\"tags\": [\"生活分享\"], \"summary\": \"一张卡通图片\", \"sentiment\": \"neutral\"}";

        Message m = new Message();
        parser.applyStructured(m, raw);

        assertEquals("生活分享", m.getAiTags());
        assertEquals("neutral", m.getAiSentiment());
        assertEquals("一张卡通图片", m.getAiSummaryShort());
    }

    @Test
    @DisplayName("普通纯 JSON 输出行为不变")
    void shouldParsePlainJson() {
        Message m = new Message();
        parser.applyStructured(m, "```json\n{\"tags\": [\"a\",\"b\"], \"summary\": \"ok\", \"sentiment\": \"positive\"}\n```");

        assertEquals("a,b", m.getAiTags());
        assertEquals("positive", m.getAiSentiment());
        assertEquals("ok", m.getAiSummaryShort());
    }

    @Test
    @DisplayName("完全不是 JSON 时退化为纯文本摘要")
    void shouldFallbackToPlainText() {
        Message m = new Message();
        parser.applyStructured(m, "抱歉，无法分析群聊消息。");

        assertEquals("抱歉，无法分析群聊消息。", m.getAiSummaryShort());
        assertNull(m.getAiTags());
    }

    @Test
    @DisplayName("括号出现在字符串里也不会截错")
    void shouldIgnoreBracesInsideStrings() {
        String raw = "{\"summary\": \"他发了 {这个} 和 \\\"引号\\\"\", \"tags\": [\"t\"], \"sentiment\": \"neutral\"}";
        Message m = new Message();
        parser.applyStructured(m, raw);

        assertEquals("他发了 {这个} 和 \"引号\"", m.getAiSummaryShort());
        assertEquals("t", m.getAiTags());
    }
}
