package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户群聊关联实体 - 记录用户和群聊的关联关系
 */
@Entity
@Table(name = "user_groups", indexes = {
    @Index(name = "idx_user_group", columnList = "userId, groupId"),
    @Index(name = "idx_group_user", columnList = "groupId, userId")
})
public class UserGroup {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 20)
    private String userId;  // 用户QQ
    
    @Column(nullable = false, length = 50)
    private String groupId;  // 群号
    
    @Column(length = 100)
    private String userRole;  // 用户在群中的角色
    
    private LocalDateTime joinedTime;  // 加入时间
    
    private LocalDateTime createdAt;
    
    private boolean active = true;  // 是否活跃
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (joinedTime == null) {
            joinedTime = LocalDateTime.now();
        }
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    
    public String getUserRole() { return userRole; }
    public void setUserRole(String userRole) { this.userRole = userRole; }
    
    public LocalDateTime getJoinedTime() { return joinedTime; }
    public void setJoinedTime(LocalDateTime joinedTime) { this.joinedTime = joinedTime; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}