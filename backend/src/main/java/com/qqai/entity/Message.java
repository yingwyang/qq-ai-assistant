package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 消息实体 - 支持分表
 */
@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_group_time", columnList = "groupId, sendTime"),
    @Index(name = "idx_user_time", columnList = "userQq, sendTime"),
    @Index(name = "idx_file_id", columnList = "fileId"),
    @Index(name = "idx_send_time", columnList = "sendTime"),
    @Index(name = "idx_group_server_time", columnList = "groupId, serverRecvMs"),
    @Index(name = "idx_user_id", columnList = "userId")
})
public class Message {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 100, unique = true)
    private String messageId;  // QQ消息唯一ID
    
    @Column(nullable = false, length = 50)
    private String groupId;  // 群号
    
    @Column(length = 100)
    private String groupName;  // 群名称
    
    @Column(name = "user_id")
    private Long userId;  // 系统用户ID（关联users.id）
    
    @Column(nullable = false, length = 20)
    private String userQq;  // 发送者QQ
    
    @Column(length = 100)
    private String userNickname;  // 发送者昵称
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    private MessageType messageType = MessageType.TEXT;  // 消息类型
    
    @Column(columnDefinition = "TEXT")
    private String content;  // 文本内容或文件描述
    
    @Column(length = 100)
    private String fileId;  // 关联的文件ID（NULL表示纯文本）
    
    @Column(length = 20)
    private String atQq;  // @的用户QQ
    
    private Long replyToMessageId;  // 回复的消息ID（数据库自增ID）

    @Column(length = 100)
    private String replyToNickname;  // 被引用消息发送者昵称

    @Column(columnDefinition = "TEXT")
    private String replyToContent;  // 被引用消息内容摘要

    @Column(columnDefinition = "TEXT")
    private String forwardContent;  // 合并转发消息的原始内容（JSON 数组）

    @Column(columnDefinition = "TEXT")
    private String miniAppContent;  // 小程序分享消息的原始内容（JSON）

    @Column(columnDefinition = "TEXT")
    private String aiSummary;  // AI 原始输出（保留用于回溯；前端展示用下面三个结构化字段）

    // ===== AI 摘要结构化字段（2026-09-16 阶段 0：由 AiAnalysisConsumer 解析模型 JSON 后回填）=====
    @Column(length = 255)
    private String aiTags;           // 逗号分隔标签
    @Column(length = 16)
    private String aiSentiment;      // positive / neutral / negative
    @Column(length = 500)
    private String aiSummaryShort;   // 一句话摘要（前端展示）
    private LocalDateTime aiSummarizedAt;  // 摘要生成时间
    @Column(length = 64)
    private String aiModel;          // 生成摘要的模型标识（可空）
    
    @Column(nullable = false)
    private LocalDateTime sendTime;  // 发送时间（展示时间，优先取自 OneBot time 转换）

    @Column(name = "raw_msg_time")
    private Long rawMsgTime;  // OneBot 上报的原始 Unix 时间戳（秒）

    @Column(name = "msg_seq")
    private Integer msgSeq;  // OneBot message_seq，用于时间接近时二次排序

    @Column(name = "server_recv_ms")
    private Long serverRecvMs;  // 服务端收到消息时的毫秒级 epoch
    
    private LocalDateTime createdAt;
    
    private boolean archived = false;  // 是否已归档

    private boolean deleted = false;  // 是否已被用户删除（软删除）

    private java.time.LocalDateTime deletedAt;  // 删除时间

    private Long deletedBy;  // 删除者用户ID（users.id）

    private boolean processed = false;  // 是否已处理

    private boolean mediaPending = false;  // 媒体是否待异步下载/转码（true=前端应显示加载占位符）
    
    @Column(nullable = false)
    private boolean isSelfMessage = false;  // 是否是登录账号发送的消息
    
    @Column(length = 20)
    private String selfQq;  // 登录账号的QQ号（接收这条消息的机器人QQ号）
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (sendTime == null) {
            sendTime = LocalDateTime.now();
        }
    }
    
    // 消息类型枚举
    public enum MessageType {
        TEXT,       // 纯文本
        IMAGE,      // 图片
        VIDEO,      // 视频
        AUDIO,      // 音频
        FILE,       // 文件
        VOICE,      // 语音
        AT,         // @消息
        REPLY,      // 回复消息
        FORWARD,    // 聊天记录（转发消息）
        APP         // 小程序分享消息
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public String getUserQq() { return userQq; }
    public void setUserQq(String userQq) { this.userQq = userQq; }
    
    public String getUserNickname() { return userNickname; }
    public void setUserNickname(String userNickname) { this.userNickname = userNickname; }
    
    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    
    public String getAtQq() { return atQq; }
    public void setAtQq(String atQq) { this.atQq = atQq; }
    
    public Long getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(Long replyToMessageId) { this.replyToMessageId = replyToMessageId; }

    public String getReplyToNickname() { return replyToNickname; }
    public void setReplyToNickname(String replyToNickname) { this.replyToNickname = replyToNickname; }

    public String getReplyToContent() { return replyToContent; }
    public void setReplyToContent(String replyToContent) { this.replyToContent = replyToContent; }

    public String getForwardContent() { return forwardContent; }
    public void setForwardContent(String forwardContent) { this.forwardContent = forwardContent; }

    public String getMiniAppContent() { return miniAppContent; }
    public void setMiniAppContent(String miniAppContent) { this.miniAppContent = miniAppContent; }

    private static final com.fasterxml.jackson.databind.ObjectMapper FORWARD_OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 供前端使用的合并转发子消息列表（JSON 字符串解析为对象数组）。
     * 不持久化，仅序列化时输出。使用 Jackson 解析以确保能被 Spring 默认序列化器正确处理。
     */
    @com.fasterxml.jackson.annotation.JsonProperty("forwardMessages")
    @jakarta.persistence.Transient
    public Object getForwardMessages() {
        if (forwardContent == null || forwardContent.isBlank()) {
            return null;
        }
        try {
            return FORWARD_OBJECT_MAPPER.readValue(forwardContent, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }

    public String getAiTags() { return aiTags; }
    public void setAiTags(String aiTags) { this.aiTags = aiTags; }

    public String getAiSentiment() { return aiSentiment; }
    public void setAiSentiment(String aiSentiment) { this.aiSentiment = aiSentiment; }

    public String getAiSummaryShort() { return aiSummaryShort; }
    public void setAiSummaryShort(String aiSummaryShort) { this.aiSummaryShort = aiSummaryShort; }

    public LocalDateTime getAiSummarizedAt() { return aiSummarizedAt; }
    public void setAiSummarizedAt(LocalDateTime aiSummarizedAt) { this.aiSummarizedAt = aiSummarizedAt; }

    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    
    public LocalDateTime getSendTime() { return sendTime; }
    public void setSendTime(LocalDateTime sendTime) { this.sendTime = sendTime; }

    public Long getRawMsgTime() { return rawMsgTime; }
    public void setRawMsgTime(Long rawMsgTime) { this.rawMsgTime = rawMsgTime; }

    public Integer getMsgSeq() { return msgSeq; }
    public void setMsgSeq(Integer msgSeq) { this.msgSeq = msgSeq; }

    public Long getServerRecvMs() { return serverRecvMs; }
    public void setServerRecvMs(Long serverRecvMs) { this.serverRecvMs = serverRecvMs; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public java.time.LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(java.time.LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }
    
    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }

    public boolean isMediaPending() { return mediaPending; }
    public void setMediaPending(boolean mediaPending) { this.mediaPending = mediaPending; }
    
    public boolean isSelfMessage() { return isSelfMessage; }
    public void setSelfMessage(boolean selfMessage) { isSelfMessage = selfMessage; }
    
    public String getSelfQq() { return selfQq; }
    public void setSelfQq(String selfQq) { this.selfQq = selfQq; }
}
