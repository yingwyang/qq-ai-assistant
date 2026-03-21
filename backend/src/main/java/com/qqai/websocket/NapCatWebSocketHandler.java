package com.qqai.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.qqai.entity.Message;
import com.qqai.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NapCat WebSocket处理器 - 接收QQ消息
 */
@Component
public class NapCatWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private MessageService messageService;

    // 存储所有连接的会话
    private static final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        System.out.println("NapCat WebSocket连接已建立: " + sessionId);
        System.out.println("当前连接数: " + sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        System.out.println("收到NapCat消息: " + payload);

        try {
            // 解析OneBot 11协议消息
            JSONObject json = JSON.parseObject(payload);
            String postType = json.getString("post_type");

            // 只处理消息事件
            if ("message".equals(postType)) {
                handleMessageEvent(json);
            }

            // 发送响应
            sendResponse(session, json);

        } catch (Exception e) {
            System.err.println("处理消息时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理消息事件
     */
    private void handleMessageEvent(JSONObject json) {
        String messageType = json.getString("message_type");

        // 只处理群聊消息
        if (!"group".equals(messageType)) {
            return;
        }

        // 提取消息信息
        Long groupId = json.getLong("group_id");
        JSONObject sender = json.getJSONObject("sender");
        Long userId = sender.getLong("user_id");
        String nickname = sender.getString("nickname");
        String rawMessage = json.getString("raw_message");
        Integer messageId = json.getInteger("message_id");

        if (groupId == null || userId == null) {
            System.err.println("消息缺少必要字段");
            return;
        }

        System.out.println("收到群聊消息: 群" + groupId + " 用户" + userId + ": " + rawMessage);

        // 创建消息实体
        Message message = new Message();
        message.setMessageId(String.valueOf(messageId));
        message.setGroupId(String.valueOf(groupId));
        message.setGroupName(String.valueOf(groupId)); // 暂时使用群号作为名称
        message.setUserQq(String.valueOf(userId));
        message.setUserNickname(nickname != null ? nickname : String.valueOf(userId));
        message.setMessageType(Message.MessageType.TEXT);
        message.setContent(rawMessage);
        message.setSendTime(LocalDateTime.now());

        // 保存到数据库
        try {
            Message savedMessage = messageService.saveMessage(message);
            System.out.println("消息已保存到数据库, ID: " + savedMessage.getId());
        } catch (Exception e) {
            System.err.println("保存消息失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 发送响应给NapCat
     */
    private void sendResponse(WebSocketSession session, JSONObject received) {
        try {
            // OneBot 11 快速操作响应
            JSONObject response = new JSONObject();
            response.put("status", "ok");
            response.put("retcode", 0);
            response.put("data", null);
            response.put("echo", received.get("echo"));

            session.sendMessage(new TextMessage(response.toJSONString()));
        } catch (IOException e) {
            System.err.println("发送响应失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        System.out.println("NapCat WebSocket连接已关闭: " + sessionId);
        System.out.println("当前连接数: " + sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        System.err.println("WebSocket传输错误: " + exception.getMessage());
        sessions.remove(session.getId());
    }

    /**
     * 获取当前连接数
     */
    public static int getConnectionCount() {
        return sessions.size();
    }
}
