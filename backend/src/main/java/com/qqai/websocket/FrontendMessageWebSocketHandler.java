package com.qqai.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class FrontendMessageWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(FrontendMessageWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> groupSubscriptions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        groupSubscriptions.put(session.getId(), new CopyOnWriteArraySet<>());
        log.debug("前端 WebSocket 已连接: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JSONObject json = JSON.parseObject(message.getPayload());
        String action = json.getString("action");

        if ("subscribe".equals(action)) {
            String groupId = json.getString("groupId");
            if (groupId != null && !groupId.isEmpty()) {
                groupSubscriptions.computeIfAbsent(session.getId(), id -> new CopyOnWriteArraySet<>()).add(groupId);
                session.sendMessage(new TextMessage("{\"type\":\"subscribed\",\"groupId\":\"" + groupId + "\"}"));
            }
        } else if ("unsubscribe".equals(action)) {
            String groupId = json.getString("groupId");
            Set<String> subs = groupSubscriptions.get(session.getId());
            if (subs != null && groupId != null) {
                subs.remove(groupId);
            }
        }
    }

    public void broadcastToGroup(String groupId, String payload) {
        groupSubscriptions.forEach((sessionId, groups) -> {
            if (groups.contains(groupId)) {
                WebSocketSession session = sessions.get(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        session.sendMessage(new TextMessage(payload));
                    } catch (IOException e) {
                        log.warn("发送 WebSocket 消息失败: {}", e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        groupSubscriptions.remove(session.getId());
        log.debug("前端 WebSocket 已断开: {}", session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输错误: {}", exception.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }
}
