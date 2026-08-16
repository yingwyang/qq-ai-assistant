package com.qqai.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.dto.webhook.MediaTaskPayload;
import com.qqai.dto.webhook.MessageParseResult;
import com.qqai.entity.Message;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.MessageRepository;
import com.qqai.repository.UserQqBindingRepository;
import com.qqai.service.GroupService;
import com.qqai.service.MessageParserService;
import com.qqai.service.MessageQueueService;
import com.qqai.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NapCat WebSocket处理器 - 接收QQ消息(OneBot 11 正向 WS)。
 *
 * 与 HTTP Webhook 通道(RootWebhookController)保持同一套入库逻辑:
 * - 去重按 (groupId, messageId) 组合,避免跨群 message_id 重复导致误丢消息;
 * - 使用 MessageParserService.parseLightweight 解析,媒体任务统一投递 RabbitMQ;
 * - 绑定系统 user_id(QQ 绑定关系)。
 */
@Component
public class NapCatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NapCatWebSocketHandler.class);

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageParserService messageParserService;

    @Autowired
    private MessageQueueService messageQueueService;

    @Autowired
    private GroupService groupService;

    @Autowired
    private UserQqBindingRepository userQqBindingRepository;

    @Value("${napcat.self-qq:}")
    private String fallbackSelfQq;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    // 存储所有连接的会话
    private static final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("NapCat WebSocket连接已建立: {}", sessionId);
        log.info("当前连接数: {}", sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("收到NapCat消息: {}", payload);

        try {
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
     * 处理消息事件(与 HTTP Webhook 通道保持逻辑一致)
     */
    private void handleMessageEvent(ObjectNode json) {
        try {
            MessageParseResult parsed = messageParserService.parseLightweight(json);

            // 只处理群聊消息
            if (parsed.getGroupId() == null) {
                return;
            }
            if (parsed.getUserId() == null) {
                log.warn("消息缺少必要字段: groupId={}", parsed.getGroupId());
                return;
            }

            String finalMessageId = parsed.getMessageId() != null ? String.valueOf(parsed.getMessageId()) : null;
            String groupIdStr = String.valueOf(parsed.getGroupId());
            if (finalMessageId != null && messageRepository.existsByMessageIdAndGroupId(finalMessageId, groupIdStr)) {
                log.debug("消息已存在，跳过: messageId={}, groupId={}", finalMessageId, groupIdStr);
                return;
            }

            String nickname = parsed.getNickname() != null && !parsed.getNickname().isEmpty()
                    ? parsed.getNickname() : String.valueOf(parsed.getUserId());
            String groupName = parsed.getGroupName() != null && !parsed.getGroupName().isEmpty()
                    ? parsed.getGroupName() : groupIdStr;

            // 当前登录 QQ
            Long selfIdLong = json.has("self_id") ? json.get("self_id").asLong() : null;
            String currentSelfQq = selfIdLong != null ? String.valueOf(selfIdLong)
                    : (fallbackSelfQq != null && !fallbackSelfQq.isEmpty() ? fallbackSelfQq : null);
            boolean isSelfMessage = currentSelfQq != null && currentSelfQq.equals(String.valueOf(parsed.getUserId()));

            log.info("收到群聊消息: 群{} 用户{}: {}", groupIdStr, parsed.getUserId(), parsed.getRawMessage());

            // 创建消息实体
            Message message = new Message();
            message.setMessageId(finalMessageId != null ? finalMessageId : String.valueOf(System.currentTimeMillis()));
            message.setGroupId(groupIdStr);
            message.setGroupName(groupName);
            message.setUserQq(String.valueOf(parsed.getUserId()));
            message.setUserNickname(nickname);
            message.setMessageType(parsed.getMsgType() != null ? parsed.getMsgType() : Message.MessageType.TEXT);
            message.setContent(parsed.getRawMessage() != null ? parsed.getRawMessage() : "");
            message.setReplyToMessageId(parsed.getReplyToMessageId());
            message.setReplyToNickname(parsed.getReplyToNickname());
            message.setReplyToContent(parsed.getReplyToContent());
            message.setForwardContent(parsed.getForwardContent());
            message.setMiniAppContent(parsed.getMiniAppContent());
            message.setRawMsgTime(parsed.getRawMsgTime());
            message.setMsgSeq(parsed.getMsgSeq());
            message.setServerRecvMs(System.currentTimeMillis());
            message.setSendTime(parsed.getRawMsgTime() != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(parsed.getRawMsgTime()), ZONE_SHANGHAI)
                    : LocalDateTime.now());
            message.setSelfQq(currentSelfQq);
            message.setSelfMessage(isSelfMessage);

            Long userId = resolveUserIdByQq(String.valueOf(parsed.getUserId()));
            message.setUserId(userId);

            // 若含媒体任务,标记 mediaPending=true(消费者完成后回填并推送 message_update)
            boolean hasMediaTasks = parsed.getMediaTasks() != null && !parsed.getMediaTasks().isEmpty();
            message.setMediaPending(hasMediaTasks);

            // 保存到数据库
            Message savedMessage;
            try {
                savedMessage = messageService.saveMessage(message);
            } catch (DataIntegrityViolationException e) {
                log.debug("消息已存在(数据库唯一约束冲突): messageId={}", finalMessageId);
                return;
            }
            log.info("消息已保存到数据库, ID: {}, mediaPending={}", savedMessage.getId(), hasMediaTasks);

            // 投递媒体任务到 RabbitMQ(与 HTTP Webhook 通道一致)
            if (hasMediaTasks) {
                for (MediaTaskPayload task : parsed.getMediaTasks()) {
                    task.setMessageId(savedMessage.getId());
                    if ("voice".equals(task.getMediaType())) {
                        messageQueueService.sendVoiceTranscode(task);
                    } else {
                        messageQueueService.sendMediaDownload(task);
                    }
                }
                log.info("已投递 {} 个媒体任务, dbId={}", parsed.getMediaTasks().size(), savedMessage.getId());
            }

            // 保存群聊信息(与 HTTP Webhook 通道一致)
            String ownerQq = isSelfMessage ? String.valueOf(parsed.getUserId())
                    : (currentSelfQq != null ? currentSelfQq : String.valueOf(parsed.getUserId()));
            try {
                groupService.saveGroupInfo(ownerQq, groupIdStr, groupName);
            } catch (Exception e) {
                log.warn("保存群聊信息失败 groupId={}: {}", groupIdStr, e.getMessage());
            }
        } catch (Exception e) {
            log.error("处理消息事件失败: {}", e.getMessage(), e);
        }
    }

    private Long resolveUserIdByQq(String qqNumber) {
        if (qqNumber == null || qqNumber.isEmpty()) {
            return null;
        }
        Optional<UserQqBinding> binding = userQqBindingRepository.findByQqNumber(qqNumber);
        return binding.map(UserQqBinding::getUserId).orElse(null);
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
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        log.info("NapCat WebSocket连接已关闭: {}", sessionId);
        log.info("当前连接数: {}", sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
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
