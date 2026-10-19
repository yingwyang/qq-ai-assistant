package com.qqai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTS 分片与 WAV 拼接测试。
 *
 * 背景（2026-10-19）：长文本原先直接截断到 500 字（丢内容），现在改为按句切分、
 * 逐段合成后拼接。这里锁住两条契约：不丢文字、不切断完整句子。
 */
class TtsAudioUtilTest {

    @Test
    @DisplayName("短文本不切分")
    void shortTextSingleChunk() {
        List<String> chunks = TtsAudioUtil.splitIntoChunks("今天天气不错。", 400);
        assertEquals(1, chunks.size());
        assertEquals("今天天气不错。", chunks.get(0));
    }

    @Test
    @DisplayName("长文本按句末切分且每段不超限")
    void splitsLongTextBySentence() {
        String text = "第一句话。" + "第二句话很长很长。".repeat(8) + "最后一句。";
        List<String> chunks = TtsAudioUtil.splitIntoChunks(text, 40);
        assertTrue(chunks.size() > 1, "应当被切分");
        for (String c : chunks) {
            assertTrue(c.length() <= 40, "片段超长: " + c.length() + " -> " + c);
        }
        assertEquals(text, String.join("", chunks), "切分后拼接必须与原文一致（不丢字）");
    }

    @Test
    @DisplayName("超长无标点句：兜底硬切且不死循环")
    void handlesUnpunctuatedLongChunk() {
        String text = "一".repeat(100);
        List<String> chunks = TtsAudioUtil.splitIntoChunks(text, 30);
        assertTrue(chunks.size() >= 4);
        assertEquals(100, String.join("", chunks).length());
    }

    @Test
    @DisplayName("WAV 拼接：头部长度正确、PCM 累加且插入静音")
    void concatWavProducesValidHeader() throws Exception {
        byte[] a = makeWav(new byte[]{1, 2, 3, 4});
        byte[] b = makeWav(new byte[]{5, 6, 7, 8});
        byte[] merged = TtsAudioUtil.concatWav(List.of(a, b), 100);
        assertEquals("RIFF", new String(merged, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(merged, 8, 4, StandardCharsets.US_ASCII));
        int dataLen = ByteBuffer.wrap(merged, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertEquals(merged.length - 44, dataLen, "data 长度必须与文件实际一致");
        // 8kHz 单声道 16bit，100ms = 800 样本 = 1600 字节
        assertEquals(4 + 1600 + 4, dataLen);
    }

    /** 造一个最小合法 WAV：8kHz / 单声道 / 16bit */
    private byte[] makeWav(byte[] pcm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        h.putInt(36 + pcm.length);
        h.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        h.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        h.putInt(16);
        h.putShort((short) 1);
        h.putShort((short) 1);
        h.putInt(8000);
        h.putInt(16000);
        h.putShort((short) 2);
        h.putShort((short) 16);
        h.put("data".getBytes(StandardCharsets.US_ASCII));
        h.putInt(pcm.length);
        out.write(h.array(), 0, 44);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }
}
