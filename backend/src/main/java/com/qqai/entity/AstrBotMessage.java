package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AstrBot 对话消息实体
 * 存储与 AstrBot 对话的每条消息
 * 借鉴 OpenCode 的 message + part 设计，使用 data 字段存储 JSON 扩展数据
 */
@Entity
@Table(name = "astrbot_messages", indexes = {
    @Index(name = "idx_msg_conversation", columnList = "conversationId, timeCreated"),
    @Index(name = "idx_msg_role", columnList = "role"),
    @Index(name = "idx_msg_time", columnList = "timeCreated")
})
public class AstrBotMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", length = 64, unique = true, nullable = false)
    private String messageId;

    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private MessageRole role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "data", columnDefinition = "TEXT")
    private String data;

    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "tokens")
    private Integer tokens;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "time_created")
    private LocalDateTime timeCreated;

    @Column(name = "time_updated")
    private LocalDateTime timeUpdated;

    /**
     * 消息角色枚举
     */
    public enum MessageRole {
        USER,       // 用户消息
        ASSISTANT,  // AI助手回复
        SYSTEM      // 系统消息
    }

    @PrePersist
    protected void onCreate() {
        timeCreated = LocalDateTime.now();
        timeUpdated = LocalDateTime.now();
        if (messageId == null) {
            messageId = generateMessageId();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        timeUpdated = LocalDateTime.now();
    }

    private String generateMessageId() {
        return "msg_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public void setRole(MessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTokens() {
        return tokens;
    }

    public void setTokens(Integer tokens) {
        this.tokens = tokens;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public LocalDateTime getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(LocalDateTime timeCreated) {
        this.timeCreated = timeCreated;
    }

    public LocalDateTime getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(LocalDateTime timeUpdated) {
        this.timeUpdated = timeUpdated;
    }
}
