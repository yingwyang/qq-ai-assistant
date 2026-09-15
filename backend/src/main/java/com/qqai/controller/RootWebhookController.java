package com.qqai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.dto.common.ApiResponse;
import com.qqai.dto.webhook.MediaTaskPayload;
import com.qqai.dto.webhook.MessageParseResult;
import com.qqai.entity.Message;
import com.qqai.entity.UserQqBinding;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 根路径 Webhook 控制器 - 接收QQ消息 (NapCat 默认上报到根路径)
 */
@RestController
public class RootWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RootWebhookController.class);

    @Autowired
    private MessageService messageService;

    @Autowired
    private GroupService groupService;

    @Autowired
    private MessageParserService messageParserService;

    @Autowired
    private MessageQueueService messageQueueService;

    @Autowired
    private UserQqBindingRepository userQqBindingRepository;

    @Value("${napcat.webhook-token:}")
    private String webhookToken;

    @Value("${napcat.self-qq:}")
    private String selfQq;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 接收 NapCat 的消息推送 - 根路径
     */
    @PostMapping("/")
    public ResponseEntity<?> receiveMessageRoot(
            @RequestBody String payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Token", required = false) String xToken,
            @RequestHeader(value = "X-OneBot-Token", required = false) String xOneBotToken,
            @RequestHeader(value = "X-Self-ID", required = false) String selfId,
            @RequestParam(value = "access_token", required = false) String accessToken) {

        // 日志脱敏:不打印原始消息内容与认证头,仅记录长度
        int payloadLen = payload != null ? payload.length() : 0;
        log.info("【根路径】收到NapCat消息, payload长度={}", payloadLen);
        // 视频调试抓包:临时输出视频事件的完整载荷,用于确认 NapCat 是否上报 CDN 直链
        if (payload != null && (payload.contains("\"video\"") || payload.contains("videoElement"))) {
            log.info("【视频调试】完整载荷: {}", payload.length() > 4000 ? payload.substring(0, 4000) : payload);
        }
        long webhookStartMs = System.currentTimeMillis();

        // 验证 Webhook Token（支持多种头格式和URL参数）。
        // fail closed:未配置 token 时直接拒绝,防止无认证的消息注入。
        if (webhookToken == null || webhookToken.isEmpty()) {
            log.error("【安全】napcat.webhook-token 未配置,拒绝接收消息。");
            return ResponseEntity.status(503)
                    .body(Map.of("error", "ServiceUnavailable", "message", "Webhook token not configured"));
        }
        boolean valid = false;
        if (authHeader != null) {
            valid = authHeader.equals("Bearer " + webhookToken) || authHeader.equals(webhookToken);
        }
        if (!valid && xToken != null) {
            valid = xToken.equals(webhookToken);
        }
        if (!valid && xOneBotToken != null) {
            valid = xOneBotToken.equals(webhookToken);
        }
        if (!valid && accessToken != null) {
            valid = accessToken.equals(webhookToken);
        }
        if (!valid) {
            log.error("【安全】Webhook Token 验证失败，拒绝接收消息。");
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized", "message", "Webhook token validation failed"));
        }

        try {
            ObjectNode json = (ObjectNode) objectMapper.readTree(payload);
            String postType = json.has("post_type") ? json.get("post_type").asText() : null;

            // 处理消息事件和发送的消息事件
            boolean isSentMessage = "message_sent".equals(postType);
            if (!"message".equals(postType) && !isSentMessage) {
                return okResponse();
            }

            String messageType = json.has("message_type") ? json.get("message_type").asText() : null;

            // 只处理群聊消息
            if (!"group".equals(messageType)) {
                return okResponse();
            }

            // 获取登录账号的QQ号（self_id）
            Long selfIdLong = json.has("self_id") ? json.get("self_id").asLong() : null;
            if (selfIdLong == null && selfId != null && !selfId.isEmpty()) {
                try {
                    selfIdLong = Long.parseLong(selfId);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }

            // 调用轻量解析服务（不执行媒体下载/转码，仅收集媒体任务）
            MessageParseResult parsed = messageParserService.parseLightweight(json);

            if (parsed.getGroupId() == null || parsed.getUserId() == null) {
                log.warn("消息缺少必要字段: groupId={}, userId={}", parsed.getGroupId(), parsed.getUserId());
                return ResponseEntity.badRequest().body("Missing required fields");
            }

            String finalMessageId = parsed.getMessageId() != null ? String.valueOf(parsed.getMessageId()) : null;
            String groupIdStr = String.valueOf(parsed.getGroupId());
            if (finalMessageId != null && messageService.existsByMessageIdAndGroupId(finalMessageId, groupIdStr)) {
                log.debug("消息已存在，跳过: messageId={}, groupId={}", finalMessageId, groupIdStr);
                Map<String, Object> okResult = new HashMap<>();
                okResult.put("status", "ok");
                okResult.put("duplicate", true);
                return ResponseEntity.ok(okResult);
            }

            String nickname = parsed.getNickname() != null && !parsed.getNickname().isEmpty()
                    ? parsed.getNickname() : String.valueOf(parsed.getUserId());
            String groupName = parsed.getGroupName() != null && !parsed.getGroupName().isEmpty()
                    ? parsed.getGroupName() : String.valueOf(parsed.getGroupId());

            // 判断是否是登录账号发送的消息
            String currentSelfQq = resolveSelfQq(selfIdLong, selfId);
            boolean isSelfMessage = currentSelfQq != null && currentSelfQq.equals(String.valueOf(parsed.getUserId()));

            if (isSelfMessage) {
                log.info("【登录账号】发送群聊消息: 群{}({}) 用户{}({})",
                        parsed.getGroupId(), groupName, parsed.getUserId(), nickname);
            } else {
                log.info("【其他成员】收到群聊消息: 群{}({}) 用户{}({})",
                        parsed.getGroupId(), groupName, parsed.getUserId(), nickname);
            }

            // 创建消息实体
            Message message = buildMessage(parsed, finalMessageId, nickname, groupName, isSelfMessage, currentSelfQq);

            // 若含媒体任务，标记 mediaPending=true（前端显示占位符，消费者完成后回填并推送 message_update）
            boolean hasMediaTasks = parsed.getMediaTasks() != null && !parsed.getMediaTasks().isEmpty();
            message.setMediaPending(hasMediaTasks);

            // 保存到数据库
            Message savedMessage;
            try {
                savedMessage = messageService.saveMessage(message);
            } catch (DataIntegrityViolationException e) {
                log.debug("消息已存在（数据库唯一约束冲突）: messageId={}", finalMessageId);
                return ResponseEntity.ok(ApiResponse.success("消息已存在"));
            }
            log.info("消息已保存到数据库, ID: {}, 类型: {}, mediaPending={}, {}",
                    savedMessage.getId(), parsed.getMsgType(), hasMediaTasks,
                    isSelfMessage ? "登录账号发送" : "其他成员发送");

            // 投递媒体任务到 RabbitMQ（图片/视频 → media.download，语音 → voice.transcode）
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

            // 保存群聊信息
            String ownerQq = isSelfMessage ? String.valueOf(parsed.getUserId())
                    : (currentSelfQq != null ? currentSelfQq : String.valueOf(parsed.getUserId()));
            groupService.saveGroupInfo(ownerQq, String.valueOf(parsed.getGroupId()), groupName);

            log.info("Webhook 处理完成, 耗时: {}ms", System.currentTimeMillis() - webhookStartMs);
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            okResult.put("id", savedMessage.getId());
            return ResponseEntity.ok(okResult);

        } catch (Exception e) {
            log.error("处理消息时出错, 耗时: {}ms, 错误: {}", System.currentTimeMillis() - webhookStartMs, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    private String resolveSelfQq(Long selfIdLong, String selfIdHeader) {
        if (selfIdLong != null) {
            return String.valueOf(selfIdLong);
        }
        if (selfIdHeader != null && !selfIdHeader.isEmpty()) {
            return selfIdHeader;
        }
        if (selfQq != null && !selfQq.isEmpty()) {
            return selfQq;
        }
        return null;
    }

    private Message buildMessage(MessageParseResult parsed, String finalMessageId,
                                  String nickname, String groupName,
                                  boolean isSelfMessage, String currentSelfQq) {
        Message message = new Message();
        message.setMessageId(finalMessageId != null ? finalMessageId : String.valueOf(System.currentTimeMillis()));
        message.setGroupId(String.valueOf(parsed.getGroupId()));
        message.setGroupName(groupName);
        message.setUserQq(String.valueOf(parsed.getUserId()));
        message.setUserNickname(nickname);
        message.setMessageType(parsed.getMsgType());
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
        message.setSelfMessage(isSelfMessage);
        message.setSelfQq(currentSelfQq);
        
        Long userId = resolveUserIdByQq(String.valueOf(parsed.getUserId()));
        message.setUserId(userId);
        
        return message;
    }
    
    private Long resolveUserIdByQq(String qqNumber) {
        if (qqNumber == null || qqNumber.isEmpty()) {
            return null;
        }
        Optional<UserQqBinding> binding = userQqBindingRepository.findByQqNumber(qqNumber);
        return binding.map(UserQqBinding::getUserId).orElse(null);
    }

    private ResponseEntity<Map<String, Object>> okResponse() {
        Map<String, Object> okResult = new HashMap<>();
        okResult.put("status", "ok");
        return ResponseEntity.ok(okResult);
    }
}
