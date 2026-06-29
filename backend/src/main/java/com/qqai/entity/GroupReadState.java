package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 群聊阅读进度实体 - 维护每个 (用户, 群聊) 对的 last_read_time
 * 用于高效计算未读消息数，不对 messages 表做逐行标记。
 */
@Entity
@Table(
    name = "group_read_state",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "group_id"})
    },
    indexes = {
        @Index(name = "idx_user_group", columnList = "user_id, group_id"),
        @Index(name = "idx_user", columnList = "user_id")
    }
)
public class GroupReadState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "group_id", nullable = false, length = 50)
    private String groupId;

    @Column(name = "last_read_time")
    private LocalDateTime lastReadTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ---- getters / setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public LocalDateTime getLastReadTime() { return lastReadTime; }
    public void setLastReadTime(LocalDateTime lastReadTime) { this.lastReadTime = lastReadTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
