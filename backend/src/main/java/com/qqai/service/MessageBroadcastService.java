package com.qqai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qqai.entity.Message;
import com.qqai.websocket.FrontendMessageWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class MessageBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(MessageBroadcastService.class);

    @Autowired
    private FrontendMessageWebSocketHandler frontendMessageWebSocketHandler;

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
            frontendMessageWebSocketHandler.broadcastToGroup(groupId, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("广播消息失败: {}", e.getMessage());
        }
    }
}
