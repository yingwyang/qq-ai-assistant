package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 摘要的结构化解析与视图组装（阶段 0 落地，阶段 1 复用）。
 *
 * <p>模型的原始输出始终完整保存在 {@code messages.ai_summary}（便于回溯/换提示词重解析），
 * 这里只负责把它拆成前端直接可用的结构化字段：标签、情感、一句话摘要。</p>
 *
 * <p>解析失败不抛异常：按纯文本处理，截断写入 {@code ai_summary_short}，
 * 这样界面上至少还能看到内容，而不是一坨 JSON 或空白。</p>
 */
@Component
public class AiSummaryParser {

    private static final Logger log = LoggerFactory.getLogger(AiSummaryParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TAGS_MAX = 255;
    private static final int SHORT_MAX = 500;
    private static final int SENTIMENT_MAX = 16;

    /** 是否已有摘要（含结构化或原始任意一种） */
    public boolean hasSummary(Message message) {
        if (message == null) return false;
        return notBlank(message.getAiSummaryShort()) || notBlank(message.getAiSummary()) || notBlank(message.getAiTags());
    }

    /**
     * 把模型输出解析进结构化字段（不负责保存）。
     * @param message 目标消息
     * @param raw     模型原始输出（可能带 ```json 围栏）
     */
    public void applyStructured(Message message, String raw) {
        if (message == null) return;
        String text = stripFence(raw);
        if (text.isEmpty()) {
            message.setAiSummarizedAt(LocalDateTime.now());
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node != null && node.isObject()) {
                JsonNode tags = node.get("tags");
                if (tags != null && tags.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode t : tags) {
                        String v = t.asText("").trim();
                        if (v.isEmpty()) continue;
                        if (sb.length() > 0) sb.append(',');
                        sb.append(v);
                    }
                    message.setAiTags(truncate(sb.toString(), TAGS_MAX));
                }
                JsonNode sentiment = node.get("sentiment");
                if (sentiment != null && !sentiment.isNull()) {
                    message.setAiSentiment(truncate(sentiment.asText("").trim(), SENTIMENT_MAX));
                }
                JsonNode summary = node.get("summary");
                String shortText = (summary != null && !summary.isNull()) ? summary.asText("") : text;
                message.setAiSummaryShort(truncate(shortText.trim(), SHORT_MAX));
                if (message.getAiSummarizedAt() == null) {
                    message.setAiSummarizedAt(LocalDateTime.now());
                }
                return;
            }
        } catch (Exception e) {
            log.debug("摘要输出不是 JSON，按纯文本处理: messageId={}", message.getId());
        }
        message.setAiSummaryShort(truncate(text, SHORT_MAX));
        if (message.getAiSummarizedAt() == null) {
            message.setAiSummarizedAt(LocalDateTime.now());
        }
    }

    /**
     * 组装给前端的摘要视图；没有任何摘要时返回 null。
     * 老数据（只有原始 JSON、没有结构化字段）在这里做兼容解析。
     */
    public Map<String, Object> toView(Message message) {
        if (message == null) return null;
        List<String> tags = splitTags(message.getAiTags());
        String sentiment = message.getAiSentiment();
        String text = message.getAiSummaryShort();

        if (!notBlank(text) && notBlank(message.getAiSummary())) {
            // 兼容老数据：从原始输出里现解析一次（不落库）
            String stripped = stripFence(message.getAiSummary());
            try {
                JsonNode node = MAPPER.readTree(stripped);
                if (node != null && node.isObject()) {
                    if (tags.isEmpty() && node.get("tags") != null && node.get("tags").isArray()) {
                        for (JsonNode t : node.get("tags")) {
                            String v = t.asText("").trim();
                            if (!v.isEmpty()) tags.add(v);
                        }
                    }
                    if (!notBlank(sentiment) && node.get("sentiment") != null && !node.get("sentiment").isNull()) {
                        sentiment = node.get("sentiment").asText("");
                    }
                    JsonNode summary = node.get("summary");
                    text = (summary != null && !summary.isNull()) ? summary.asText("") : stripped;
                } else {
                    text = stripped;
                }
            } catch (Exception e) {
                text = stripped;
            }
        }

        if (!notBlank(text) && tags.isEmpty() && !notBlank(sentiment)) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tags", tags);
        view.put("summary", text == null ? "" : text);
        view.put("sentiment", sentiment == null ? "" : sentiment);
        return view;
    }

    private List<String> splitTags(String raw) {
        List<String> out = new ArrayList<>();
        if (!notBlank(raw)) return out;
        for (String part : raw.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String stripFence(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        return text;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
