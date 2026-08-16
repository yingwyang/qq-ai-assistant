package com.qqai.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.config.JwtHandshakeInterceptor;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.UserQqBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 前端消息推送 WebSocket。
 *
 * 安全模型:
 * 1. 握手由 JwtHandshakeInterceptor 完成认证(签名/黑名单/tokenVersion),
 *    认证通过后 userId 写入 session attributes;
 * 2. subscribe 时服务端强制校验该用户绑定的 QQ 是否拥有目标群,
 *    杜绝"任意登录用户订阅任意群消息流"的越权问题。
 */
@Component
public class FrontendMessageWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(FrontendMessageWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> groupSubscriptions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserQqBindingRepository userQqBindingRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        groupSubscriptions.put(session.getId(), new CopyOnWriteArraySet<>());
        log.debug("前端 WebSocket 已连接: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());
        String action = json.has("action") ? json.get("action").asText() : null;

        if ("subscribe".equals(action)) {
            String groupId = json.has("groupId") ? json.get("groupId").asText() : null;
            if (groupId != null && !groupId.isEmpty()) {
                if (!canSubscribe(session, groupId)) {
                    log.warn("【安全】拒绝订阅:userId={} 无权访问群 {}", currentUserId(session), groupId);
                    session.sendMessage(new TextMessage(
                            "{\"type\":\"subscribe_denied\",\"groupId\":\"" + groupId + "\"}"));
                    return;
                }
                groupSubscriptions.computeIfAbsent(session.getId(), id -> new CopyOnWriteArraySet<>()).add(groupId);
                session.sendMessage(new TextMessage("{\"type\":\"subscribed\",\"groupId\":\"" + groupId + "\"}"));
            }
        } else if ("unsubscribe".equals(action)) {
            String groupId = json.has("groupId") ? json.get("groupId").asText() : null;
            Set<String> subs = groupSubscriptions.get(session.getId());
            if (subs != null && groupId != null) {
                subs.remove(groupId);
            }
        }
    }

    /**
     * 订阅权限校验:会话必须已完成 JWT 握手,且当前用户绑定的 QQ 拥有该群。
     */
    private boolean canSubscribe(WebSocketSession session, String groupId) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return false;
        }
        try {
            List<UserQqBinding> bindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
            if (bindings == null || bindings.isEmpty()) {
                return false;
            }
            List<String> qqList = bindings.stream()
                    .filter(b -> b != null && b.getQqNumber() != null && !b.getQqNumber().isBlank())
                    .map(UserQqBinding::getQqNumber)
                    .toList();
            if (qqList.isEmpty()) {
                return false;
            }
            return groupRepository.countByGroupIdAndOwnerQqInAndActiveTrue(groupId, qqList) > 0;
        } catch (Exception e) {
            log.warn("订阅权限校验失败 groupId={}: {}", groupId, e.getMessage());
            return false;
        }
    }

    private Long currentUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USER_ID);
        return userId instanceof Long l ? l : null;
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
