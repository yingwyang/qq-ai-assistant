package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AstrBot 对话会话实体
 * 存储与 AstrBot 的对话会话信息
 */
@Entity
@Table(name = "astrbot_conversations", indexes = {
    @Index(name = "idx_conv_group_user", columnList = "groupId, userId"),
    @Index(name = "idx_conv_time", columnList = "timeUpdated"),
    @Index(name = "idx_conv_archived", columnList = "archived"),
    @Index(name = "idx_conv_user", columnList = "userId")
})
public class AstrBotConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", length = 64, unique = true, nullable = false)
    private String conversationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "group_id", length = 50)
    private String groupId;

    @Column(name = "user_qq", length = 20)
    private String userQq;

    @Column(name = "user_nickname", length = 100)
    private String userNickname;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "message_count")
    private Integer messageCount = 0;

    @Column(name = "total_tokens")
    private Integer totalTokens = 0;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "archived")
    private Boolean archived = false;

    @Column(name = "time_created")
    private LocalDateTime timeCreated;

    @Column(name = "time_updated")
    private LocalDateTime timeUpdated;

    @Column(name = "time_archived")
    private LocalDateTime timeArchived;

    @PrePersist
    protected void onCreate() {
        timeCreated = LocalDateTime.now();
        timeUpdated = LocalDateTime.now();
        if (conversationId == null) {
            conversationId = generateConversationId();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        timeUpdated = LocalDateTime.now();
    }

    private String generateConversationId() {
        return "conv_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getUserQq() {
        return userQq;
    }

    public void setUserQq(String userQq) {
        this.userQq = userQq;
    }

    public String getUserNickname() {
        return userNickname;
    }

    public void setUserNickname(String userNickname) {
        this.userNickname = userNickname;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount) {
        this.messageCount = messageCount;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getContextJson() {
        return contextJson;
    }

    public void setContextJson(String contextJson) {
        this.contextJson = contextJson;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
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

    public LocalDateTime getTimeArchived() {
        return timeArchived;
    }

    public void setTimeArchived(LocalDateTime timeArchived) {
        this.timeArchived = timeArchived;
    }
}
