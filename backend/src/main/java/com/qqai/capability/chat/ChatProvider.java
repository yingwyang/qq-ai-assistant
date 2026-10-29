package com.qqai.capability.chat;

import com.qqai.capability.HealthStatus;

/**
 * 对话能力契约。实现必须自行处理会话/人格/模型/流式解析等细节，
 * 业务层只依赖本接口。
 */
public interface ChatProvider {

    ChatReply chat(ChatRequest request);

    HealthStatus health();
}
