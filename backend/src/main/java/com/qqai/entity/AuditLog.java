package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 审计日志实体 - 记录关键管理操作
 */
@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_username", columnList = "username"),
    @Index(name = "idx_audit_action", columnList = "action"),
    @Index(name = "idx_audit_timestamp", columnList = "timestamp")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String username;      // 操作人

    @Column(length = 50)
    private String action;        // 操作类型: ROLE_CHANGE, COMPONENT_START, COMPONENT_STOP, FILE_DELETE, USER_DELETE, etc.

    @Column(length = 255)
    private String target;        // 操作目标

    @Column(length = 20)
    private String result;        // 操作结果: SUCCESS, FAILURE

    @Column(length = 1000)
    private String detail;        // 详细信息

    private LocalDateTime timestamp;

    public AuditLog() {
    }

    public AuditLog(String username, String action, String target, String result, String detail, LocalDateTime timestamp) {
        this.username = username;
        this.action = action;
        this.target = target;
        this.result = result;
        this.detail = detail;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
