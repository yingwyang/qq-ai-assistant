package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户QQ账号绑定实体 - 支持一个用户绑定多个QQ账号
 */
@Entity
@Table(name = "user_qq_bindings", indexes = {
    @Index(name = "idx_user_id", columnList = "userId"),
    @Index(name = "idx_qq_number", columnList = "qqNumber"),
    @Index(name = "idx_user_qq", columnList = "userId, qqNumber", unique = true)
})
public class UserQqBinding {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long userId;  // 用户ID（关联users表）
    
    @Column(nullable = false, length = 20)
    private String qqNumber;  // 绑定的QQ号
    
    @Column(length = 100)
    private String nickname;  // QQ昵称
    
    @Column(length = 255)
    private String avatar;  // QQ头像URL
    
    @Column(nullable = false)
    private boolean active = true;  // 是否激活
    
    @Column(nullable = false)
    private boolean isDefault = false;  // 是否为默认账号
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public String getQqNumber() { return qqNumber; }
    public void setQqNumber(String qqNumber) { this.qqNumber = qqNumber; }
    
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
