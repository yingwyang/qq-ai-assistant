package com.qqai.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 每个用户自己的机器人（一个 QQ 账号 / 一个 NapCat 实例）。 */
@Entity
@Table(name = "bots", indexes = {
        @Index(name = "idx_bot_owner", columnList = "owner_user_id"),
        @Index(name = "idx_bot_qq", columnList = "qq_number"),
        @Index(name = "idx_bot_ports", columnList = "napcat_port")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_bot_napcat_port", columnNames = "napcat_port"),
        @UniqueConstraint(name = "uk_bot_onebot_port", columnNames = "onebot_port"),
        @UniqueConstraint(name = "uk_bot_qq", columnNames = "qq_number")
})
public class Bot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "qq_number", length = 20)
    private String qqNumber;

    /** PROVISIONING / STOPPED / STARTING / RUNNING / DEGRADED / FAILED */
    @Column(nullable = false, length = 16)
    private String status = "PROVISIONING";

    @Column(name = "napcat_port", nullable = false)
    private Integer napcatPort;

    @Column(name = "onebot_port", nullable = false)
    private Integer onebotPort;

    /** 每个 bot 独立的 OneBot token，webhook 用它反查 bot，避免串号。 */
    @JsonIgnore
    @Column(name = "onebot_token", nullable = false, length = 64)
    private String onebotToken;

    @Column(nullable = false, length = 255)
    private String workdir;

    @Column(name = "last_error", length = 512)
    private String lastError;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getQqNumber() { return qqNumber; }
    public void setQqNumber(String qqNumber) { this.qqNumber = qqNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getNapcatPort() { return napcatPort; }
    public void setNapcatPort(Integer napcatPort) { this.napcatPort = napcatPort; }
    public Integer getOnebotPort() { return onebotPort; }
    public void setOnebotPort(Integer onebotPort) { this.onebotPort = onebotPort; }
    public String getOnebotToken() { return onebotToken; }
    public void setOnebotToken(String onebotToken) { this.onebotToken = onebotToken; }
    public String getWorkdir() { return workdir; }
    public void setWorkdir(String workdir) { this.workdir = workdir; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
}
