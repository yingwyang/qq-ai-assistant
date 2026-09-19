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

    @Column(length = 1000)
    private String astrbotApiKey;  // AstrBot API Key

    @Column(length = 1000)
    private String llmApiKey;  // 大模型 API Key

    @Column(length = 500)
    private String llmBaseUrl;  // 大模型 API Base URL

    @Column(length = 200)
    private String llmModel;  // 当前选中的大模型

    @Column(columnDefinition = "TEXT")
    private String llmModels;  // 模型列表JSON格式: ["model1", "model2"]

    @Column(columnDefinition = "TEXT")
    private String providers;  // 提供商列表JSON格式: [{"name":"siliconflow","apiKey":"xxx","baseUrl":"xxx"}]

    /**
     * 用户选定的 AstrBot 人格（persona_id），"双层提示词"里的人层。
     *
     * <p>岗位（任务规则、输出格式）由后端 prompts.yml 提供，人（人格、语气）由 AstrBot 提供。
     * 因为 /api/v1/chat 没有 persona 字段，实际落地是：按人格生成配置档案
     * （{@code qqai-p-<slug>-text} / {@code -image}），请求时用 config_name 指定。</p>
     */
    @Column(length = 200)
    private String astrbotPersonaId;

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

    public String getAstrbotApiKey() { return astrbotApiKey; }
    public void setAstrbotApiKey(String astrbotApiKey) { this.astrbotApiKey = astrbotApiKey; }

    public String getLlmApiKey() { return llmApiKey; }
    public void setLlmApiKey(String llmApiKey) { this.llmApiKey = llmApiKey; }

    public String getLlmBaseUrl() { return llmBaseUrl; }
    public void setLlmBaseUrl(String llmBaseUrl) { this.llmBaseUrl = llmBaseUrl; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public String getLlmModels() { return llmModels; }
    public void setLlmModels(String llmModels) { this.llmModels = llmModels; }

    public String getProviders() { return providers; }
    public void setProviders(String providers) { this.providers = providers; }

    public String getAstrbotPersonaId() { return astrbotPersonaId; }
    public void setAstrbotPersonaId(String astrbotPersonaId) { this.astrbotPersonaId = astrbotPersonaId; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
