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
    @Index(name = "idx_send_time", columnList = "sendTime")
})
public class Message {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 100)
    private String messageId;  // QQ消息唯一ID
    
    @Column(nullable = false, length = 50)
    private String groupId;  // 群号
    
    @Column(length = 200)
    private String groupName;  // 群名称
    
    @Column(nullable = false, length = 20)
    private String userQq;  // 发送者QQ
    
    @Column(length = 100)
    private String userNickname;  // 发送者昵称
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageType messageType = MessageType.TEXT;  // 消息类型
    
    @Column(columnDefinition = "TEXT")
    private String content;  // 文本内容或文件描述
    
    @Column(length = 100)
    private String fileId;  // 关联的文件ID（NULL表示纯文本）
    
    @Column(length = 20)
    private String atQq;  // @的用户QQ
    
    private Long replyToMessageId;  // 回复的消息ID
    
    @Column(columnDefinition = "TEXT")
    private String aiSummary;  // AI总结内容
    
    @Column(nullable = false)
    private LocalDateTime sendTime;  // 发送时间
    
    private LocalDateTime createdAt;
    
    private boolean archived = false;  // 是否已归档
    
    private boolean processed = false;  // 是否已处理
    
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
        REPLY       // 回复消息
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
    
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    
    public LocalDateTime getSendTime() { return sendTime; }
    public void setSendTime(LocalDateTime sendTime) { this.sendTime = sendTime; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    
    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }
    
    public boolean isSelfMessage() { return isSelfMessage; }
    public void setSelfMessage(boolean selfMessage) { isSelfMessage = selfMessage; }
    
    public String getSelfQq() { return selfQq; }
    public void setSelfQq(String selfQq) { this.selfQq = selfQq; }
}
