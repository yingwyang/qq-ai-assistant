package com.qqai.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.entity.Message;
import com.qqai.service.MessageService;
import com.qqai.service.NapCatService;
import com.qqai.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NapCat WebSocket处理器 - 接收QQ消息
 */
@Component
public class NapCatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NapCatWebSocketHandler.class);

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private NapCatService napCatService;

    @Value("${napcat.self-qq:}")
    private String fallbackSelfQq;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    // 存储所有连接的会话
    private static final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("NapCat WebSocket连接已建立: {}", sessionId);
        log.info("当前连接数: {}", sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到NapCat消息: {}", payload);

        try {
            // 解析OneBot 11协议消息
            ObjectNode json = (ObjectNode) objectMapper.readTree(payload);
            String postType = json.has("post_type") ? json.get("post_type").asText() : null;

            // 只处理消息事件（包括自己发送的消息 message_sent）
            if ("message".equals(postType) || "message_sent".equals(postType)) {
                handleMessageEvent(json);
            }

            // 发送响应
            sendResponse(session, json);

        } catch (Exception e) {
            log.error("处理消息时出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理消息事件
     */
    private void handleMessageEvent(ObjectNode json) {
        String messageType = json.has("message_type") ? json.get("message_type").asText() : null;

        // 只处理群聊消息
        if (!"group".equals(messageType)) {
            return;
        }

        // 提取消息信息
        Long groupId = json.has("group_id") ? json.get("group_id").asLong() : null;
        ObjectNode sender = (ObjectNode) json.get("sender");
        Long userId = (sender != null && sender.has("user_id")) ? sender.get("user_id").asLong() : null;
        String nickname = (sender != null && sender.has("nickname")) ? sender.get("nickname").asText() : null;
        String rawMessage = json.has("raw_message") ? json.get("raw_message").asText() : null;
        Integer messageId = json.has("message_id") ? json.get("message_id").asInt() : null;
        Long rawMsgTime = json.has("time") ? json.get("time").asLong() : null;
        Integer msgSeq = json.has("message_seq") ? json.get("message_seq").asInt() : null;

        // 动态获取当前登录QQ：优先使用上报的 self_id，否则使用配置兜底
        Long selfIdLong = json.has("self_id") ? json.get("self_id").asLong() : null;
        String currentSelfQq = selfIdLong != null ? String.valueOf(selfIdLong)
                : (fallbackSelfQq != null && !fallbackSelfQq.isEmpty() ? fallbackSelfQq : null);

        if (groupId == null || userId == null) {
            log.warn("消息缺少必要字段");
            return;
        }

        String finalMessageId = messageId != null ? String.valueOf(messageId) : null;
        if (finalMessageId != null && messageRepository.existsByMessageId(finalMessageId)) {
            log.debug("消息已存在，跳过: messageId={}", finalMessageId);
            return;
        }

        log.info("收到群聊消息: 群{} 用户{}: {}", groupId, userId, rawMessage);

        // 创建消息实体
        Message message = new Message();
        message.setMessageId(finalMessageId);
        message.setGroupId(String.valueOf(groupId));
        message.setGroupName(String.valueOf(groupId)); // 暂时使用群号作为名称
        message.setUserQq(String.valueOf(userId));
        message.setUserNickname(nickname != null ? nickname : String.valueOf(userId));
        Message.MessageType msgType = resolveMessageType(rawMessage);
        message.setMessageType(msgType);
        message.setContent(rawMessage);
        if (msgType == Message.MessageType.FORWARD) {
            // 优先从 NapCat 解析后的 message 数组中提取子消息（需要 parseMultMsg=true）
            ArrayNode parsedForwardMessages = napCatService.extractForwardMessagesFromPayload(json);
            if (parsedForwardMessages != null && !parsedForwardMessages.isEmpty()) {
                napCatService.downloadForwardMediaToLocal(parsedForwardMessages, String.valueOf(groupId));
                message.setForwardContent(parsedForwardMessages.toString());
                log.info("WebSocket 从 message 数组解析到合并转发消息详情, 共 {} 条子消息", parsedForwardMessages.size());
            } else {
                // Fallback：尝试通过 API 拉取（需要 NapCat 本地缓存该消息）
                String forwardId = extractForwardId(rawMessage);
                if (forwardId != null && !forwardId.isEmpty()) {
                    try {
                        ArrayNode forwardMessages = napCatService.getForwardMsg(forwardId);
                        if (forwardMessages != null && !forwardMessages.isEmpty()) {
                            napCatService.downloadForwardMediaToLocal(forwardMessages, String.valueOf(groupId));
                            message.setForwardContent(forwardMessages.toString());
                            log.info("WebSocket 合并转发消息详情已拉取, forwardId={}, 共 {} 条子消息", forwardId, forwardMessages.size());
                        }
                    } catch (Exception e) {
                        log.error("WebSocket 拉取合并转发消息详情失败, forwardId={}: {}", forwardId, e.getMessage());
                    }
                }
            }
        }
        if (msgType == Message.MessageType.REPLY) {
            Long replyQqMessageId = extractReplyMessageId(rawMessage);
            if (replyQqMessageId != null) {
                java.util.Optional<Message> replied = messageRepository.findByMessageId(String.valueOf(replyQqMessageId));
                if (replied.isPresent()) {
                    Message target = replied.get();
                    message.setReplyToMessageId(target.getId());
                    message.setReplyToNickname(target.getUserNickname());
                    message.setReplyToContent(buildReplyToContent(target));
                }
            }
        }
        message.setRawMsgTime(rawMsgTime);
        message.setMsgSeq(msgSeq);
        message.setServerRecvMs(System.currentTimeMillis());
        message.setSendTime(rawMsgTime != null
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(rawMsgTime), ZONE_SHANGHAI)
                : LocalDateTime.now());
        message.setSelfQq(currentSelfQq);
        message.setSelfMessage(currentSelfQq != null && currentSelfQq.equals(String.valueOf(userId)));

        // 保存到数据库
        try {
            Message savedMessage = messageService.saveMessage(message);
            log.info("消息已保存到数据库, ID: {}", savedMessage.getId());
        } catch (Exception e) {
            log.error("保存消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 根据 raw_message 中的 CQ 码解析消息类型。
     * 只处理转发和回复类型，其他保持 TEXT（具体文件类型由 Webhook 控制器处理）。
     */
    private Message.MessageType resolveMessageType(String rawMessage) {
        if (rawMessage == null || !rawMessage.contains("[CQ:")) {
            return Message.MessageType.TEXT;
        }
        if (rawMessage.contains("[CQ:forward")) {
            return Message.MessageType.FORWARD;
        }
        if (rawMessage.contains("[CQ:reply")) {
            return Message.MessageType.REPLY;
        }
        return Message.MessageType.TEXT;
    }

    /**
     * 从 raw_message 中提取 [CQ:forward,id=...] 的转发消息 ID。
     */
    private String extractForwardId(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        try {
            int start = rawMessage.indexOf("[CQ:forward");
            if (start < 0) {
                return null;
            }
            int end = rawMessage.indexOf(']', start);
            if (end < 0) {
                return null;
            }
            String cq = rawMessage.substring(start, end + 1);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("id=([^,\\]]+)");
            java.util.regex.Matcher matcher = pattern.matcher(cq);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            log.error("解析 forward CQ 码失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 raw_message 中提取 [CQ:reply,id=...] 的被引用消息 ID。
     * 如果 id 不是数字字符串则返回 null。
     */
    private Long extractReplyMessageId(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        try {
            int start = rawMessage.indexOf("[CQ:reply");
            if (start < 0) {
                return null;
            }
            int end = rawMessage.indexOf(']', start);
            if (end < 0) {
                return null;
            }
            String cq = rawMessage.substring(start, end + 1);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("id=([^,\\]]+)");
            java.util.regex.Matcher matcher = pattern.matcher(cq);
            if (matcher.find()) {
                String idStr = matcher.group(1).trim();
                return Long.parseLong(idStr);
            }
        } catch (NumberFormatException e) {
            // id 不是数字，忽略
        } catch (Exception e) {
            log.error("解析 reply CQ 码失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 构造被引用消息的内容摘要。
     * 优先使用目标消息自身内容（移除 CQ:reply 码后）；
     * 如果目标消息本身也是一条引用且没有额外文本，则回退到目标所引用的内容，
     * 避免"引用的引用"在预览中显示为空或错误索引。
     */
    private String buildReplyToContent(Message target) {
        if (target == null) {
            return null;
        }
        String content = target.getContent();
        if (content != null) {
            content = content.replaceAll("\\[CQ:reply[^\\]]*\\]", "").trim();
            if (!content.isEmpty()) {
                return content;
            }
        }
        String innerReplyContent = target.getReplyToContent();
        if (innerReplyContent != null) {
            innerReplyContent = innerReplyContent.replaceAll("\\[CQ:reply[^\\]]*\\]", "").trim();
            if (!innerReplyContent.isEmpty()) {
                return innerReplyContent;
            }
        }
        return null;
    }

    /**
     * 发送响应给NapCat
     */
    private void sendResponse(WebSocketSession session, ObjectNode received) {
        try {
            // OneBot 11 快速操作响应
            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", "ok");
            response.put("retcode", 0);
            response.set("data", null);
            response.set("echo", received.get("echo"));

            session.sendMessage(new TextMessage(response.toString()));
        } catch (IOException e) {
            log.error("发送响应失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        log.info("NapCat WebSocket连接已关闭: {}", sessionId);
        log.info("当前连接数: {}", sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误: {}", exception.getMessage());
        sessions.remove(session.getId());
    }

    /**
     * 获取当前连接数
     */
    public static int getConnectionCount() {
        return sessions.size();
    }
}
