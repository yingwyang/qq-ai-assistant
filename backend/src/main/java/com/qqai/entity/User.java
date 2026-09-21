package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户实体 - 记录登录账号信息
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_username", columnList = "username"),
    @Index(name = "idx_nickname", columnList = "nickname")
})
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true, length = 20)
    private String username;  // 用户名/账号
    
    @Column(length = 100)
    private String nickname;  // 昵称
    
    @Column(length = 255)
    private String avatar;  // 头像URL
    
    @Column(length = 255)
    private String token;  // 认证令牌
    
    @Column(nullable = false, length = 255)
    private String password;  // 密码（加密存储）
    
    @Column(length = 50)
    private String role = "USER";  // 用户角色：ADMIN, USER

    @Column(length = 255)
    private String email;  // 邮箱

    @Column(length = 11)
    private Integer tokenVersion = 0;  // Token 版本号，用于登出/改密码后批量失效
    
    private LocalDateTime lastLoginTime;  // 最后登录时间
    
    private LocalDateTime createdAt;
    
    private boolean active = true;  // 是否活跃
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (lastLoginTime == null) {
            lastLoginTime = LocalDateTime.now();
        }
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    
    // 敏感字段：即便某处直接把 User 实体当响应体返回，也不能把密码哈希与令牌序列化出去
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    
    public LocalDateTime getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(LocalDateTime lastLoginTime) { this.lastLoginTime = lastLoginTime; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Integer getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(Integer tokenVersion) { this.tokenVersion = tokenVersion; }
}