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

    /**
     * 触发本次分析的用户 id（「人层」用他选定的人格）。
     *
     * <p>批量补摘要由某个登录用户手动触发，队列消费者没有请求上下文，
     * 只能靠载荷把这个身份带过去；为 null 时用内置默认人格档案。</p>
     */
    private Long requestedBy;

    public AiAnalysisPayload() {}

    public AiAnalysisPayload(Long messageId, String content) {
        this(messageId, content, null);
    }

    public AiAnalysisPayload(Long messageId, String content, Long requestedBy) {
        this.messageId = messageId;
        this.content = content;
        this.requestedBy = requestedBy;
    }

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long requestedBy) { this.requestedBy = requestedBy; }

    @Override
    public String toString() {
        return "AiAnalysisPayload{messageId=" + messageId +
                ", contentLen=" + (content != null ? content.length() : 0) +
                ", requestedBy=" + requestedBy + '}';
    }
}
