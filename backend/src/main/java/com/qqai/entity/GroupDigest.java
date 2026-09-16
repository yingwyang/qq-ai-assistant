package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 群日报（AI 摘要阶段 3）。
 *
 * <p>每群每天最多一条：{@code UNIQUE(group_id, digest_date)} 保证「同群同天只生成一次」，
 * 重复调用直接返回已有记录（幂等），不会重复消耗大模型额度。</p>
 *
 * <p>风格与同目录 {@link Message} 保持一致：手写 getter/setter，不使用 Lombok。</p>
 */
@Entity
@Table(name = "group_digest", uniqueConstraints = {
    @UniqueConstraint(name = "uk_group_digest_group_date", columnNames = {"group_id", "digest_date"})
})
public class GroupDigest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, length = 32)
    private String groupId;  // 群号

    @Column(name = "digest_date", nullable = false)
    private LocalDate digestDate;  // 归属日期（当天）

    @Column(columnDefinition = "TEXT")
    private String summary;  // 一句话总览（80 字内）

    @Column(length = 255)
    private String tags;  // 逗号分隔标签，如「组队,攻略」

    @Column(length = 16)
    private String sentiment;  // positive / neutral / negative

    @Column(name = "message_count")
    private Integer messageCount;  // 参与生成的消息条数

    @Column(length = 64)
    private String model;  // 生成日报的模型标识（可空）

    @Column(name = "created_at")
    private LocalDateTime createdAt;  // 生成时间

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public LocalDate getDigestDate() { return digestDate; }
    public void setDigestDate(LocalDate digestDate) { this.digestDate = digestDate; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }

    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
