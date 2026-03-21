package com.qqai.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.qqai.entity.Message;
import com.qqai.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * NapCat Webhook 控制器 - 接收QQ消息
 */
@RestController
@RequestMapping("/api/napcat")
public class NapCatWebhookController {

    @Autowired
    private MessageService messageService;

    @Value("${napcat.webhook-token:}")
    private String webhookToken;

    /**
     * 接收 NapCat 的消息推送 - 根路径
     */
    @PostMapping("")
    public ResponseEntity<?> receiveMessageRoot(
            @RequestBody String payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Self-ID", required = false) String selfId) {
        return receiveMessage(payload, authHeader, selfId);
    }

    /**
     * 接收 NapCat 的消息推送
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> receiveMessage(
            @RequestBody String payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Self-ID", required = false) String selfId) {

        System.out.println("收到NapCat消息: " + payload);
        System.out.println("Authorization: " + authHeader);
        System.out.println("X-Self-ID: " + selfId);

        // 验证 Token
        if (webhookToken != null && !webhookToken.isEmpty()) {
            if (authHeader == null || !authHeader.equals("Bearer " + webhookToken)) {
                System.err.println("Token 验证失败");
                return ResponseEntity.status(401).body("{\"error\":\"Unauthorized\"}");
            }
        }

        try {
            JSONObject json = JSON.parseObject(payload);
            String postType = json.getString("post_type");

            // 只处理消息事件
            if (!"message".equals(postType)) {
                return ResponseEntity.ok("{\"status\":\"ok\"}");
            }

            String messageType = json.getString("message_type");

            // 只处理群聊消息
            if (!"group".equals(messageType)) {
                return ResponseEntity.ok("{\"status\":\"ok\"}");
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
                return ResponseEntity.badRequest().body("Missing required fields");
            }

            System.out.println("收到群聊消息: 群" + groupId + " 用户" + userId + ": " + rawMessage);

            // 创建消息实体
            Message message = new Message();
            message.setMessageId(String.valueOf(messageId));
            message.setGroupId(String.valueOf(groupId));
            message.setGroupName(String.valueOf(groupId));
            message.setUserQq(String.valueOf(userId));
            message.setUserNickname(nickname != null ? nickname : String.valueOf(userId));
            message.setMessageType(Message.MessageType.TEXT);
            message.setContent(rawMessage);
            message.setSendTime(LocalDateTime.now());

            // 保存到数据库
            Message savedMessage = messageService.saveMessage(message);
            System.out.println("消息已保存到数据库, ID: " + savedMessage.getId());

            return ResponseEntity.ok("{\"status\":\"ok\",\"id\":" + savedMessage.getId() + "}");

        } catch (Exception e) {
            System.err.println("处理消息时出错: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
