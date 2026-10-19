package com.qqai.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * TTS 音频与文本的分片工具。
 *
 * <p>背景：GPT-SoVITS 单次合成长文本会明显变慢且断句不可控，而直接截断
 * （历史实现是 &gt;500 字截断加省略号）会丢内容。这里改成：
 * 按句末标点切分成不超过 maxChars 的片段，逐段合成后再拼接成一条音频。</p>
 *
 * <p>WAV 拼接只处理 PCM 数据段：GPT-SoVITS 返回的是 32kHz/单声道/16bit，
 * 各段格式一致，因此保留第一段的头，累加 data 段并重算长度即可。</p>
 */
final class TtsAudioUtil {

    private TtsAudioUtil() {
    }

    /** 句末标点（在这些字符之后切分是自然的） */
    private static final String SENTENCE_END = "。！？；!?;…\n";

    /**
     * 按句末标点把文本切成不超过 maxChars 的片段。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>长度不超过 {@code maxChars} 时原样返回（不切）；</li>
     *   <li>优先在句末标点处切分，不切断完整句子；</li>
     *   <li>单句自身就超过 {@code maxChars} 时，退化为在逗号处切，仍超长才硬切。</li>
     * </ul>
     */
    static List<String> splitIntoChunks(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String s = text.trim();
        if (maxChars <= 0 || s.length() <= maxChars) {
            chunks.add(s);
            return chunks;
        }
        int start = 0;
        while (start < s.length()) {
            int remaining = s.length() - start;
            if (remaining <= maxChars) {
                chunks.add(s.substring(start).trim());
                break;
            }
            int limit = start + maxChars;
            int cut = lastIndexOfAny(s, SENTENCE_END, start, limit);
            if (cut < 0) {
                // 窗口内没有句末标点：退到逗号/顿号，避免切断完整句子
                cut = lastIndexOfAny(s, "，,、", start, limit);
            }
            if (cut < 0) {
                // 仍然是超长无标点句：硬切（保底，不然会死循环）
                cut = limit - 1;
            }
            int end = cut + 1;
            String chunk = s.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start = end;
        }
        return chunks;
    }

    /** 在 [from, to) 区间内找最后一个属于 chars 的字符位置；找不到返回 -1 */
    private static int lastIndexOfAny(String s, String chars, int from, int to) {
        for (int i = Math.min(to, s.length()) - 1; i >= from; i--) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 拼接多段 WAV（PCM）。各段必须是相同采样率/声道/位深。
     *
     * @param wavs      各段 WAV 字节
     * @param silenceMs 段间静音毫秒（&lt;=0 则不插）
     */
    static byte[] concatWav(List<byte[]> wavs, int silenceMs) throws Exception {
        if (wavs == null || wavs.isEmpty()) {
            throw new IllegalArgumentException("没有可拼接的音频");
        }
        if (wavs.size() == 1) {
            return wavs.get(0);
        }
        byte[] first = wavs.get(0);
        int dataOffset = findDataOffset(first);
        int channels = readShort(first, 22);
        int sampleRate = readInt(first, 24);
        int bitsPerSample = readShort(first, 34);

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        pcm.write(first, dataOffset, first.length - dataOffset);

        byte[] gap = null;
        if (silenceMs > 0) {
            int bytesPerFrame = channels * bitsPerSample / 8;
            gap = new byte[Math.max(1, sampleRate * silenceMs / 1000) * bytesPerFrame];
        }
        for (int i = 1; i < wavs.size(); i++) {
            if (gap != null) {
                pcm.write(gap, 0, gap.length);
            }
            byte[] w = wavs.get(i);
            pcm.write(w, findDataOffset(w), w.length - findDataOffset(w));
        }

        byte[] pcmBytes = pcm.toByteArray();
        int dataLen = pcmBytes.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.write(intLE(36 + dataLen));
        out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        out.write(intLE(16));
        out.write(shortLE(1));                       // PCM
        out.write(shortLE(channels));
        out.write(intLE(sampleRate));
        out.write(intLE(sampleRate * channels * bitsPerSample / 8)); // byte rate
        out.write(shortLE(channels * bitsPerSample / 8));            // block align
        out.write(shortLE(bitsPerSample));
        out.write("data".getBytes(StandardCharsets.US_ASCII));
        out.write(intLE(dataLen));
        out.write(pcmBytes);
        return out.toByteArray();
    }

    /** 找到 WAV 中 data 段的起始偏移（跳过 chunk 头 8 字节） */
    private static int findDataOffset(byte[] wav) {
        int i = 12;
        while (i + 8 <= wav.length) {
            String id = new String(wav, i, 4, StandardCharsets.US_ASCII);
            int size = readInt(wav, i + 4);
            if ("data".equals(id)) {
                return i + 8;
            }
            i += 8 + size + (size % 2);
        }
        // 兜底：按标准 44 字节头
        return 44;
    }

    private static short readShort(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    private static int readInt(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static byte[] intLE(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] shortLE(int v) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) v).array();
    }
}
