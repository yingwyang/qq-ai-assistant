package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户设置实体 - 存储用户的个性化设置
 */
@Entity
@Table(name = "user_settings", indexes = {
    @Index(name = "idx_user_id", columnList = "userId")
})
public class UserSettings {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true, length = 50)
    private String userId;  // 用户ID
    
    @Column(length = 100)
    private String botName = "AstrBot 助手";  // Bot名称
    
    @Column(length = 500)
    private String botAvatar = "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100";  // Bot头像
    
    @Column(length = 500)
    private String userAvatar = "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100";  // 用户头像
    
    private LocalDateTime updatedAt;
    
    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getBotName() { return botName; }
    public void setBotName(String botName) { this.botName = botName; }
    
    public String getBotAvatar() { return botAvatar; }
    public void setBotAvatar(String botAvatar) { this.botAvatar = botAvatar; }
    
    public String getUserAvatar() { return userAvatar; }
    public void setUserAvatar(String userAvatar) { this.userAvatar = userAvatar; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
