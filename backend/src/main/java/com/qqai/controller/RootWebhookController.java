package com.qqai.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.qqai.entity.Message;
import com.qqai.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 根路径 Webhook 控制器 - 接收QQ消息 (NapCat 默认上报到根路径)
 */
@RestController
public class RootWebhookController {

    @Autowired
    private MessageService messageService;

    @Value("${napcat.webhook-token:}")
    private String webhookToken;

    /**
     * 接收 NapCat 的消息推送 - 根路径
     */
    @PostMapping("/")
    public ResponseEntity<?> receiveMessageRoot(
            @RequestBody String payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Self-ID", required = false) String selfId) {

        System.out.println("【根路径】收到NapCat消息: " + payload.substring(0, Math.min(500, payload.length())));
        System.out.println("Authorization: " + authHeader);
        System.out.println("X-Self-ID: " + selfId);

        // 验证 Token - NapCat 可能通过 X-Self-ID 或其他方式验证
        if (webhookToken != null && !webhookToken.isEmpty()) {
            boolean valid = false;

            // 方式1: Authorization: Bearer <token>
            if (authHeader != null && authHeader.equals("Bearer " + webhookToken)) {
                valid = true;
            }
            // 方式2: Authorization: <token> (没有Bearer前缀)
            else if (authHeader != null && authHeader.equals(webhookToken)) {
                valid = true;
            }
            // 方式3: 通过 X-Self-ID 验证 (NapCat 的 QQ 号)
            else if (selfId != null && !selfId.isEmpty()) {
                System.out.println("使用 X-Self-ID 验证: " + selfId);
                valid = true;
            }

            if (!valid) {
                System.err.println("Token 验证失败");
                return ResponseEntity.status(401).body("{\"error\":\"Unauthorized\"}");
            }
        }

        try {
            JSONObject json = JSON.parseObject(payload);
            String postType = json.getString("post_type");

            // 处理消息事件和发送的消息事件
            boolean isSentMessage = "message_sent".equals(postType);
            if (!"message".equals(postType) && !isSentMessage) {
                return ResponseEntity.ok("{\"status\":\"ok\"}");
            }

            String messageType = json.getString("message_type");

            // 只处理群聊消息
            if (!"group".equals(messageType)) {
                return ResponseEntity.ok("{\"status\":\"ok\"}");
            }
            
            // 获取登录账号的QQ号（self_id）
            Long selfIdLong = json.getLong("self_id");
            if (selfIdLong == null && selfId != null && !selfId.isEmpty()) {
                try {
                    selfIdLong = Long.parseLong(selfId);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }

            // 提取消息信息 - 支持多种格式
            Long groupId = null;
            String groupName = null;
            Long userId = null;
            String nickname = null;
            String rawMessage = null;
            Integer messageId = null;
            Message.MessageType msgType = Message.MessageType.TEXT;

            // 尝试标准 OneBot 11 格式
            groupId = json.getLong("group_id");
            groupName = json.getString("group_name");
            JSONObject sender = json.getJSONObject("sender");
            if (sender != null) {
                userId = sender.getLong("user_id");
                nickname = sender.getString("nickname");
            }
            rawMessage = json.getString("raw_message");
            messageId = json.getInteger("message_id");

            // 如果标准格式没有，尝试 NapCat 格式
            if (groupId == null) {
                groupId = json.getLong("peerUin");
            }
            if (groupName == null) {
                groupName = json.getString("peerName");
            }
            if (userId == null) {
                userId = json.getLong("senderUin");
            }
            if (nickname == null) {
                nickname = json.getString("sendNickName");
            }
            if (messageId == null) {
                messageId = json.getInteger("msgId");
            }

            // 检查 raw_message 是否包含 CQ 码（NapCat 已经格式化好的消息）
            boolean hasRawMessage = rawMessage != null && !rawMessage.isEmpty();
            boolean isImageFromRaw = hasRawMessage && rawMessage.contains("[CQ:image");
            
            // 从 NapCat elements 数组中提取消息内容和类型
            // 但对于图片消息，优先使用 raw_message 中的完整 CQ 码
            if (!isImageFromRaw) {
                JSONArray elements = json.getJSONArray("elements");
                if (elements != null && !elements.isEmpty()) {
                    StringBuilder msgBuilder = new StringBuilder();
                    for (int i = 0; i < elements.size(); i++) {
                        JSONObject element = elements.getJSONObject(i);
                        if (element == null) continue;
                        
                        Integer elementType = element.getInteger("elementType");
                        if (elementType == null) continue;
                        
                        switch (elementType) {
                            case 1: // 文本消息
                                JSONObject textElement = element.getJSONObject("textElement");
                                if (textElement != null) {
                                    String content = textElement.getString("content");
                                    if (content != null) {
                                        msgBuilder.append(content);
                                    }
                                }
                                break;
                            case 2: // 图片消息
                                JSONObject picElement = element.getJSONObject("picElement");
                                if (picElement != null) {
                                    String picUrl = picElement.getString("sourcePath");
                                    if (picUrl == null || picUrl.isEmpty()) {
                                        picUrl = picElement.getString("thumbPath");
                                    }
                                    // 构建 CQ 码格式的图片消息
                                    if (picUrl != null && !picUrl.isEmpty()) {
                                        msgBuilder.append("[CQ:image,file=").append(picElement.getString("fileName")).append(",url=").append(picUrl).append("]");
                                        msgType = Message.MessageType.IMAGE;
                                    }
                                }
                                break;
                            case 3: // 语音消息
                                msgType = Message.MessageType.VOICE;
                                break;
                            case 4: // 视频消息
                                msgType = Message.MessageType.VIDEO;
                                break;
                            case 6: // 文件消息
                                msgType = Message.MessageType.FILE;
                                break;
                        }
                    }
                    if (msgBuilder.length() > 0) {
                        rawMessage = msgBuilder.toString();
                    }
                }
            }

            // 如果 raw_message 中有 CQ 码，解析消息类型
            if (rawMessage != null && rawMessage.contains("[CQ:")) {
                if (rawMessage.contains("[CQ:image")) {
                    msgType = Message.MessageType.IMAGE;
                } else if (rawMessage.contains("[CQ:record") || rawMessage.contains("[CQ:voice")) {
                    msgType = Message.MessageType.VOICE;
                } else if (rawMessage.contains("[CQ:video")) {
                    msgType = Message.MessageType.VIDEO;
                } else if (rawMessage.contains("[CQ:file")) {
                    msgType = Message.MessageType.FILE;
                }
            }

            if (groupId == null || userId == null) {
                System.err.println("消息缺少必要字段: groupId=" + groupId + ", userId=" + userId);
                return ResponseEntity.badRequest().body("Missing required fields");
            }

            // 如果没有昵称，使用 QQ 号
            if (nickname == null || nickname.isEmpty()) {
                nickname = String.valueOf(userId);
            }
            
            // 如果没有群聊名称，使用群聊ID
            if (groupName == null || groupName.isEmpty()) {
                groupName = String.valueOf(groupId);
            }

            // 判断是否是发送的消息
            boolean isSelfMessage = selfIdLong != null && selfIdLong.equals(userId);
            if (isSentMessage || isSelfMessage) {
                System.out.println("发送群聊消息: 群" + groupId + "(" + groupName + ") 用户" + userId + "(" + nickname + "): " + rawMessage);
            } else {
                System.out.println("收到群聊消息: 群" + groupId + "(" + groupName + ") 用户" + userId + "(" + nickname + "): " + rawMessage);
            }

            // 创建消息实体
            Message message = new Message();
            message.setMessageId(String.valueOf(messageId != null ? messageId : System.currentTimeMillis()));
            message.setGroupId(String.valueOf(groupId));
            message.setGroupName(groupName);
            message.setUserQq(String.valueOf(userId));
            message.setUserNickname(nickname);
            message.setMessageType(msgType);
            message.setContent(rawMessage != null ? rawMessage : "");
            message.setSendTime(LocalDateTime.now());

            // 保存到数据库
            Message savedMessage = messageService.saveMessage(message);
            System.out.println("消息已保存到数据库, ID: " + savedMessage.getId() + ", 类型: " + msgType + (isSentMessage || isSelfMessage ? ", 发送消息" : ", 接收消息"));

            return ResponseEntity.ok("{\"status\":\"ok\",\"id\":" + savedMessage.getId() + "}");

        } catch (Exception e) {
            System.err.println("处理消息时出错: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
