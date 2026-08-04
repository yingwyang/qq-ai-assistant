package com.qqai.dto.webhook;

import java.io.Serializable;

/**
 * 广播任务载荷，由 MessageBroadcastService 投递到 qqai.broadcast (Fanout Exchange)，
 * 由 BroadcastConsumer 消费后调用 FrontendMessageWebSocketHandler.broadcastToGroup。
 *
 * 采用预序列化 JSON 字符串方式，避免 Message 实体中 LocalDateTime 等类型
 * 在 RabbitMQ Jackson 反序列化时的兼容性问题。
 */
public class BroadcastPayload implements Serializable {

    /** 目标群号 */
    private String groupId;

    /** 预序列化的 JSON 字符串（含 type / groupId / message），直接传给 WebSocket 广播 */
    private String jsonPayload;

    public BroadcastPayload() {}

    public BroadcastPayload(String groupId, String jsonPayload) {
        this.groupId = groupId;
        this.jsonPayload = jsonPayload;
    }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getJsonPayload() { return jsonPayload; }
    public void setJsonPayload(String jsonPayload) { this.jsonPayload = jsonPayload; }

    @Override
    public String toString() {
        return "BroadcastPayload{groupId=" + groupId +
                ", jsonLen=" + (jsonPayload != null ? jsonPayload.length() : 0) + '}';
    }
}
