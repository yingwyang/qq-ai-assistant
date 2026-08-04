package com.qqai.dto.webhook;

import java.io.Serializable;

/**
 * AI 分析任务载荷，由 MessageService 投递到 ai.analysis.queue，
 * 由 AiAnalysisConsumer 消费后调用 AstrBotService 生成摘要。
 */
public class AiAnalysisPayload implements Serializable {

    /** 数据库 messages.id */
    private Long messageId;

    /** 待分析的消息文本内容 */
    private String content;

    public AiAnalysisPayload() {}

    public AiAnalysisPayload(Long messageId, String content) {
        this.messageId = messageId;
        this.content = content;
    }

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    @Override
    public String toString() {
        return "AiAnalysisPayload{messageId=" + messageId +
                ", contentLen=" + (content != null ? content.length() : 0) + '}';
    }
}
