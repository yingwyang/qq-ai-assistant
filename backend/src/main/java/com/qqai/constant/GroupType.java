package com.qqai.constant;

import java.util.Arrays;
import java.util.List;

/**
 * 群聊类型枚举 - 用于按群类型定制 LLM Prompt 策略
 * 数据库存储为 VARCHAR(32)，此处仅作常量定义与元数据查询
 */
public enum GroupType {

    GAME("游戏", "🎮", List.of("meme-dictionary", "integration-guide")),
    STUDY("学习", "📚", List.of("summary", "topic-trend")),
    WORK("工作", "💼", List.of("summary", "integration-guide")),
    HOBBY("兴趣", "🎯", List.of("meme-dictionary", "social-graph")),
    LIFE("生活", "🏠", List.of("summary", "topic-trend")),
    SOCIAL("社交", "🍻", List.of("social-graph", "persona-match")),
    OTHER("其他", "💬", List.of("summary"));

    private final String label;
    private final String icon;
    private final List<String> recommendedAnalysisTypes;

    GroupType(String label, String icon, List<String> recommendedAnalysisTypes) {
        this.label = label;
        this.icon = icon;
        this.recommendedAnalysisTypes = recommendedAnalysisTypes;
    }

    public String getLabel() { return label; }
    public String getIcon() { return icon; }
    public List<String> getRecommendedAnalysisTypes() { return recommendedAnalysisTypes; }
    public String getCode() { return name(); }

    /**
     * 从字符串安全解析为 GroupType，未知值返回 OTHER
     */
    public static GroupType fromString(String code) {
        if (code == null || code.isBlank()) return OTHER;
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(OTHER);
    }

    /**
     * 判断字符串是否为有效群类型（非 OTHER）
     */
    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) return false;
        return Arrays.stream(values())
                .anyMatch(t -> t.name().equalsIgnoreCase(code.trim()) && t != OTHER);
    }
}
