package com.qqai.capability.chat;

/** 对话回复：正文 + 可选 token 用量（用于计费）。 */
public record ChatReply(String text,
                        Integer promptTokens,
                        Integer completionTokens,
                        Integer totalTokens) {
}
