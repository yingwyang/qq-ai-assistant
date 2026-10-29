package com.qqai.capability.chat;

import java.util.List;

/**
 * 与实现无关的对话请求：只描述业务语义，不出现 AstrBot 的 session/config 等概念。
 */
public record ChatRequest(
        Long userId,
        String conversationId,
        String text,
        List<String> imageUrls,
        String model,
        String configName,
        String configId,
        String userQq,
        ChatPurpose purpose) {

    public boolean hasImage() {
        return imageUrls != null && !imageUrls.isEmpty();
    }
}
