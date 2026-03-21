package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 群聊实体 - 记录群聊信息
 */
@Entity
@Table(name = "groups", indexes = {
    @Index(name = "idx_group_id", columnList = "groupId"),
    @Index(name = "idx_group_name", columnList = "groupName")
})
public class Group {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true, length = 50)
    private String groupId;  // 群号
    
    @Column(length = 200)
    private String groupName;  // 群名称
    
    @Column(length = 255)
    private String avatar;  // 群头像URL
    
    private Integer memberCount;  // 成员数量
    
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
    
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    
    public Integer getMemberCount() { return memberCount; }
    public void setMemberCount(Integer memberCount) { this.memberCount = memberCount; }
    
    public LocalDateTime getJoinedTime() { return joinedTime; }
    public void setJoinedTime(LocalDateTime joinedTime) { this.joinedTime = joinedTime; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}