package com.qqai.capability.im;

/** 发送结果（与实现无关）：成功标志、上游消息 id、失败原因。 */
public record ImSendResult(boolean success, String messageId, String errorMessage) {
}
