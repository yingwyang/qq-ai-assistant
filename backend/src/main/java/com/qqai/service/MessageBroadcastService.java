package com.qqai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qqai.dto.webhook.BroadcastPayload;
import com.qqai.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息广播服务
 *
 * 改造后通过 RabbitMQ Fanout Exchange 中转广播：
 *  1. 将消息序列化为 JSON（含 type / groupId / message）
 *  2. 封装为 BroadcastPayload 投递到 qqai.broadcast (Fanout)
 *  3. BroadcastConsumer 消费后调 FrontendMessageWebSocketHandler.broadcastToGroup
 *
 * 解耦后广播变为异步，调用方（saveMessage / Consumer 等）不再阻塞等待 WebSocket 推送。
 */
@Service
public class MessageBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(MessageBroadcastService.class);

    @Autowired
    private MessageQueueService messageQueueService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public void broadcastNewMessage(Message message) {
        broadcast(message.getGroupId(), "new_message", message);
    }

    public void broadcastMessageUpdate(Message message) {
        broadcast(message.getGroupId(), "message_update", message);
    }

    private void broadcast(String groupId, String type, Message message) {
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", type);
            payload.put("groupId", groupId);
            payload.put("message", message);
            String json = objectMapper.writeValueAsString(payload);

            // 投递到 Fanout Exchange，由 BroadcastConsumer 异步推送到 WebSocket
            messageQueueService.sendBroadcast(new BroadcastPayload(groupId, json));
        } catch (Exception e) {
            log.warn("广播消息失败: {}", e.getMessage());
        }
    }
}
