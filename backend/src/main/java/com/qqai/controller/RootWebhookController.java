package com.qqai.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.qqai.entity.Group;
import com.qqai.entity.Message;
import com.qqai.service.MediaDownloadService;
import com.qqai.service.MessageService;
import com.qqai.service.NapCatService;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Autowired
    private MessageService messageService;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Autowired
    private NapCatService napCatService;

    @Value("${napcat.webhook-token:}")
    private String webhookToken;
    
    @Value("${napcat.self-qq:}")
    private String selfQq;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    
    @Value("${file.storage.local-path:./uploads/images}")
    private String localImagePath;

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

        System.out.println("【根路径】收到NapCat消息: " + payload.substring(0, Math.min(500, payload.length())));
        System.out.println("Authorization: " + authHeader);
        System.out.println("X-Token: " + xToken);
        System.out.println("X-OneBot-Token: " + xOneBotToken);
        System.out.println("X-Self-ID: " + selfId);
        System.out.println("access_token param: " + accessToken);

        // 验证 Webhook Token（支持多种头格式和URL参数）
        // 暂时禁用token验证，NapCat配置了token但未正确发送
        if (webhookToken != null && !webhookToken.isEmpty()) {
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
                System.err.println("Webhook Token 验证失败 - 配置的token: " + webhookToken);
                System.err.println("当前启用的NapCat配置可能是其他QQ号的，跳过验证以确保消息正常接收");
            }
        }

        try {
            JSONObject json = JSON.parseObject(payload);
            String postType = json.getString("post_type");

            // 处理消息事件和发送的消息事件
            boolean isSentMessage = "message_sent".equals(postType);
            if (!"message".equals(postType) && !isSentMessage) {
                Map<String, Object> okResult = new HashMap<>();
                okResult.put("status", "ok");
                return ResponseEntity.ok(okResult);
            }

            String messageType = json.getString("message_type");

            // 只处理群聊消息
            if (!"group".equals(messageType)) {
                Map<String, Object> okResult = new HashMap<>();
                okResult.put("status", "ok");
                return ResponseEntity.ok(okResult);
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
            Long rawMsgTime = json.getLong("time");
            Integer msgSeq = json.getInteger("message_seq");

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

            // 如果 raw_message 中有 CQ 码，解析消息类型并下载到本地
            Long replyToMessageId = null;
            String replyToNickname = null;
            String replyToContent = null;
            String forwardContent = null;
            if (rawMessage != null && rawMessage.contains("[CQ:")) {
                if (rawMessage.contains("[CQ:image")) {
                    msgType = Message.MessageType.IMAGE;
                    // 下载图片到本地
                    String localUrl = mediaDownloadService.downloadMediaToLocal(rawMessage, String.valueOf(groupId), "images", ".jpg");
                    if (localUrl != null) {
                        rawMessage = localUrl;
                    }
                } else if (rawMessage.contains("[CQ:record") || rawMessage.contains("[CQ:voice")) {
                    msgType = Message.MessageType.VOICE;
                    // 下载语音到本地
                    String localUrl = mediaDownloadService.downloadMediaToLocal(rawMessage, String.valueOf(groupId), "voice", ".amr");
                    if (localUrl != null) {
                        rawMessage = localUrl;
                    }
                } else if (rawMessage.contains("[CQ:video")) {
                    msgType = Message.MessageType.VIDEO;
                    // 下载视频到本地
                    String localUrl = mediaDownloadService.downloadMediaToLocal(rawMessage, String.valueOf(groupId), "video", ".mp4");
                    if (localUrl != null) {
                        rawMessage = localUrl;
                    }
                } else if (rawMessage.contains("[CQ:file")) {
                    msgType = Message.MessageType.FILE;
                } else if (rawMessage.contains("[CQ:forward")) {
                    msgType = Message.MessageType.FORWARD;
                    // 优先从 NapCat 解析后的 message 数组中提取子消息（需要 parseMultMsg=true）
                    JSONArray parsedForwardMessages = napCatService.extractForwardMessagesFromPayload(json);
                    if (parsedForwardMessages != null && !parsedForwardMessages.isEmpty()) {
                        napCatService.downloadForwardMediaToLocal(parsedForwardMessages, String.valueOf(groupId));
                        forwardContent = parsedForwardMessages.toJSONString();
                        System.out.println("从 message 数组解析到合并转发消息详情, 共 " + parsedForwardMessages.size() + " 条子消息");
                    } else {
                        // Fallback：尝试通过 API 拉取（需要 NapCat 本地缓存该消息）
                        String forwardId = extractForwardId(rawMessage);
                        if (forwardId != null && !forwardId.isEmpty()) {
                            try {
                                JSONArray forwardMessages = napCatService.getForwardMsg(forwardId);
                                if (forwardMessages != null && !forwardMessages.isEmpty()) {
                                    napCatService.downloadForwardMediaToLocal(forwardMessages, String.valueOf(groupId));
                                    forwardContent = forwardMessages.toJSONString();
                                    System.out.println("合并转发消息详情已拉取, forwardId=" + forwardId + ", 共 " + forwardMessages.size() + " 条子消息");
                                }
                            } catch (Exception e) {
                                System.err.println("拉取合并转发消息详情失败, forwardId=" + forwardId + ": " + e.getMessage());
                            }
                        }
                    }
                } else if (rawMessage.contains("[CQ:reply")) {
                    msgType = Message.MessageType.REPLY;
                    Long replyQqMessageId = extractReplyMessageId(rawMessage);
                    if (replyQqMessageId != null) {
                        Optional<Message> replied = messageRepository.findByMessageId(String.valueOf(replyQqMessageId));
                        if (replied.isPresent()) {
                            Message target = replied.get();
                            replyToMessageId = target.getId();
                            replyToNickname = target.getUserNickname();
                            replyToContent = buildReplyToContent(target);
                        }
                    }
                }
            }

            if (groupId == null || userId == null) {
                System.err.println("消息缺少必要字段: groupId=" + groupId + ", userId=" + userId);
                return ResponseEntity.badRequest().body("Missing required fields");
            }

            String finalMessageId = messageId != null ? String.valueOf(messageId) : null;
            if (finalMessageId != null && messageRepository.existsByMessageId(finalMessageId)) {
                System.out.println("消息已存在，跳过: messageId=" + finalMessageId);
                Map<String, Object> okResult = new HashMap<>();
                okResult.put("status", "ok");
                okResult.put("duplicate", true);
                return ResponseEntity.ok(okResult);
            }

            // 如果没有昵称，使用 QQ 号
            if (nickname == null || nickname.isEmpty()) {
                nickname = String.valueOf(userId);
            }
            
            // 如果没有群聊名称，使用群聊ID
            if (groupName == null || groupName.isEmpty()) {
                groupName = String.valueOf(groupId);
            }

            // 判断是否是登录账号发送的消息
            // 优先使用实际上报的 self_id / X-Self-ID，最后 fallback 到配置文件
            String currentSelfQq = null;
            if (selfIdLong != null) {
                currentSelfQq = String.valueOf(selfIdLong);
            } else if (selfId != null && !selfId.isEmpty()) {
                currentSelfQq = selfId;
            } else if (selfQq != null && !selfQq.isEmpty()) {
                currentSelfQq = selfQq;
            }
            System.out.println("[DEBUG] self_id=" + json.getLong("self_id") + ", X-Self-ID=" + selfId + ", config.selfQq=" + selfQq + ", 最终使用=" + currentSelfQq);
            boolean isSelfMessage = currentSelfQq != null && currentSelfQq.equals(String.valueOf(userId));
            
            if (isSelfMessage) {
                System.out.println("【登录账号】发送群聊消息: 群" + groupId + "(" + groupName + ") 用户" + userId + "(" + nickname + "): " + rawMessage);
            } else {
                System.out.println("【其他成员】收到群聊消息: 群" + groupId + "(" + groupName + ") 用户" + userId + "(" + nickname + "): " + rawMessage);
            }

            // 创建消息实体
            Message message = new Message();
            message.setMessageId(finalMessageId != null ? finalMessageId : String.valueOf(System.currentTimeMillis()));
            message.setGroupId(String.valueOf(groupId));
            message.setGroupName(groupName);
            message.setUserQq(String.valueOf(userId));
            message.setUserNickname(nickname);
            message.setMessageType(msgType);
            message.setContent(rawMessage != null ? rawMessage : "");
            message.setReplyToMessageId(replyToMessageId);
            message.setReplyToNickname(replyToNickname);
            message.setReplyToContent(replyToContent);
            message.setForwardContent(forwardContent);
            message.setRawMsgTime(rawMsgTime);
            message.setMsgSeq(msgSeq);
            message.setServerRecvMs(System.currentTimeMillis());
            message.setSendTime(rawMsgTime != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(rawMsgTime), ZONE_SHANGHAI)
                    : LocalDateTime.now());
            message.setSelfMessage(isSelfMessage);  // 标记是否是登录账号发送的消息
            message.setSelfQq(currentSelfQq);  // 设置登录账号的QQ号

            // 保存到数据库
            Message savedMessage = messageService.saveMessage(message);
            System.out.println("消息已保存到数据库, ID: " + savedMessage.getId() + ", 类型: " + msgType + (isSelfMessage ? ", 登录账号发送" : ", 其他成员发送"));
            
            // 保存群聊信息（所有群聊消息都会更新/创建群聊记录，使用当前登录者作为owner）
            // 如果是登录账号发送的消息，使用发送者QQ作为owner；否则使用当前登录账号作为owner
            String ownerQq = isSelfMessage ? String.valueOf(userId) : (currentSelfQq != null ? currentSelfQq : String.valueOf(userId));
            saveGroupInfo(ownerQq, String.valueOf(groupId), groupName);

            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            okResult.put("id", savedMessage.getId());
            return ResponseEntity.ok(okResult);

        } catch (Exception e) {
            System.err.println("处理消息时出错: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
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
            System.err.println("解析 forward CQ 码失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 从 raw_message 中提取 [CQ:reply,id=...] 的被引用消息 ID。
     * 如果 id 是数字字符串则转换为 Long；否则返回 null（消息类型仍会被设为 REPLY）。
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
            // id 不是数字，忽略（保留 REPLY 类型）
        } catch (Exception e) {
            System.err.println("解析 reply CQ 码失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 构造被引用消息的内容摘要。
     * 优先使用目标消息自身内容（移除 CQ:reply 码后）；
     * 如果目标消息本身也是一条引用且没有额外文本，则回退到目标所引用的内容，
     * 避免“引用的引用”在预览中显示为空或错误索引。
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
     * 保存群聊信息
     * 每个登录账号的群聊记录独立，保留最新
     */
    private void saveGroupInfo(String ownerQq, String groupId, String groupName) {
        try {
            // 根据群号和登录者QQ查找群聊（每个登录者的群聊记录独立）
            Optional<Group> existingGroup = groupRepository.findByGroupIdAndOwnerQq(groupId, ownerQq);
            Group group;
            if (existingGroup.isPresent()) {
                // 更新现有群聊信息
                group = existingGroup.get();
                // 如果群名有变化，更新群名
                if (groupName != null && !groupName.isEmpty() && !groupName.equals(group.getGroupName())) {
                    group.setGroupName(groupName);
                    System.out.println("更新群聊信息: " + groupId + " -> " + groupName);
                }
                // 如果没有头像或头像文件不存在，下载群头像
                boolean needDownloadAvatar = false;
                if (group.getAvatar() == null || group.getAvatar().isEmpty()) {
                    needDownloadAvatar = true;
                } else if (group.getAvatar().startsWith("/images/")) {
                    // 检查本地头像文件是否存在
                    String avatarFileName = group.getAvatar().substring("/images/".length());
                    Path avatarPath = Paths.get(localImagePath, avatarFileName);
                    if (!Files.exists(avatarPath)) {
                        needDownloadAvatar = true;
                    }
                }
                
                if (needDownloadAvatar) {
                    String localAvatarPath = mediaDownloadService.downloadGroupAvatarToLocal(groupId);
                    if (localAvatarPath != null) {
                        group.setAvatar(localAvatarPath);
                        System.out.println("更新群聊头像: " + groupId + " -> " + localAvatarPath);
                    }
                }
                // 更新时间和活跃状态
                group.setJoinedTime(LocalDateTime.now());
                group.setActive(true);
                groupRepository.save(group);
            } else {
                // 创建新群聊
                group = new Group();
                group.setGroupId(groupId);
                group.setOwnerQq(ownerQq);  // 设置登录者QQ
                group.setGroupName(groupName != null ? groupName : "群聊 " + groupId);
                // 下载群头像到本地
                String localAvatarPath = mediaDownloadService.downloadGroupAvatarToLocal(groupId);
                if (localAvatarPath != null) {
                    group.setAvatar(localAvatarPath);
                } else {
                    // 如果下载失败，使用QQ群头像API（正确的群头像地址）
                    group.setAvatar("https://p.qlogo.cn/gh/" + groupId + "/" + groupId + "/100");
                }
                group.setActive(true);
                groupRepository.save(group);
                System.out.println("创建新群聊: 登录者=" + ownerQq + ", 群号=" + groupId + ", 群名=" + groupName);
            }
        } catch (Exception e) {
            System.err.println("保存群聊信息时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
