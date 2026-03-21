package com.qqai.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.qqai.entity.Group;
import com.qqai.entity.Message;
import com.qqai.service.MessageService;
import com.qqai.repository.GroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根路径 Webhook 控制器 - 接收QQ消息 (NapCat 默认上报到根路径)
 */
@RestController
public class RootWebhookController {

    @Autowired
    private MessageService messageService;

    @Autowired
    private GroupRepository groupRepository;

    @Value("${napcat.webhook-token:}")
    private String webhookToken;
    
    @Value("${napcat.self-qq:}")
    private String selfQq;
    
    @Value("${file.storage.local-path:./uploads/images}")
    private String localImagePath;

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

            // 如果 raw_message 中有 CQ 码，解析消息类型并下载到本地
            if (rawMessage != null && rawMessage.contains("[CQ:")) {
                if (rawMessage.contains("[CQ:image")) {
                    msgType = Message.MessageType.IMAGE;
                    // 下载图片到本地
                    String localUrl = downloadMediaToLocal(rawMessage, String.valueOf(groupId), "images", ".jpg");
                    if (localUrl != null) {
                        rawMessage = localUrl;
                    }
                } else if (rawMessage.contains("[CQ:record") || rawMessage.contains("[CQ:voice")) {
                    msgType = Message.MessageType.VOICE;
                    // 下载语音到本地
                    String localUrl = downloadMediaToLocal(rawMessage, String.valueOf(groupId), "voice", ".amr");
                    if (localUrl != null) {
                        rawMessage = localUrl;
                    }
                } else if (rawMessage.contains("[CQ:video")) {
                    msgType = Message.MessageType.VIDEO;
                    // 下载视频到本地
                    String localUrl = downloadMediaToLocal(rawMessage, String.valueOf(groupId), "video", ".mp4");
                    if (localUrl != null) {
                        rawMessage = localUrl;
                    }
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

            // 判断是否是登录账号发送的消息
            // 优先使用配置的selfQq，如果没有则使用self_id
            String currentSelfQq = (selfQq != null && !selfQq.isEmpty()) ? selfQq : (selfIdLong != null ? String.valueOf(selfIdLong) : null);
            boolean isSelfMessage = currentSelfQq != null && currentSelfQq.equals(String.valueOf(userId));
            
            if (isSelfMessage) {
                System.out.println("【登录账号】发送群聊消息: 群" + groupId + "(" + groupName + ") 用户" + userId + "(" + nickname + "): " + rawMessage);
            } else {
                System.out.println("【其他成员】收到群聊消息: 群" + groupId + "(" + groupName + ") 用户" + userId + "(" + nickname + "): " + rawMessage);
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
            message.setSelfMessage(isSelfMessage);  // 标记是否是登录账号发送的消息
            message.setSelfQq(currentSelfQq);  // 设置登录账号的QQ号

            // 保存到数据库
            Message savedMessage = messageService.saveMessage(message);
            System.out.println("消息已保存到数据库, ID: " + savedMessage.getId() + ", 类型: " + msgType + (isSelfMessage ? ", 登录账号发送" : ", 其他成员发送"));
            
            // 保存群聊信息（所有群聊消息都会更新/创建群聊记录，使用当前登录者作为owner）
            // 如果是登录账号发送的消息，使用发送者QQ作为owner；否则使用当前登录账号作为owner
            String ownerQq = isSelfMessage ? String.valueOf(userId) : (currentSelfQq != null ? currentSelfQq : String.valueOf(userId));
            saveGroupInfo(ownerQq, String.valueOf(groupId), groupName);

            return ResponseEntity.ok("{\"status\":\"ok\",\"id\":" + savedMessage.getId() + "}");

        } catch (Exception e) {
            System.err.println("处理消息时出错: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
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
                    String localAvatarPath = downloadGroupAvatarToLocal(groupId);
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
                String localAvatarPath = downloadGroupAvatarToLocal(groupId);
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
    
    /**
     * 下载媒体文件到本地存储
     * @param cqMessage CQ码格式的消息
     * @param groupId 群号
     * @param mediaType 媒体类型（images/voice/video）
     * @param defaultExt 默认文件扩展名
     * @return 本地文件路径，下载失败返回null
     */
    private String downloadMediaToLocal(String cqMessage, String groupId, String mediaType, String defaultExt) {
        try {
            // 从CQ码中提取URL
            String mediaUrl = extractUrlFromCQ(cqMessage);
            if (mediaUrl == null || mediaUrl.isEmpty()) {
                System.err.println("无法从CQ码中提取URL: " + cqMessage);
                return null;
            }
            
            // 创建本地存储目录，按群号和日期组织
            String dateFolder = LocalDateTime.now().toLocalDate().toString();
            Path groupDir = Paths.get(localImagePath, mediaType, groupId, dateFolder);
            if (!Files.exists(groupDir)) {
                Files.createDirectories(groupDir);
            }
            
            // 生成文件名（使用UUID避免重名）
            String fileName = UUID.randomUUID().toString() + defaultExt;
            Path localPath = groupDir.resolve(fileName);
            
            // 下载文件
            URL url = new URL(mediaUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(60000); // 视频可能需要更长时间
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(localPath.toFile())) {
                    
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    
                    // 如果是语音文件，转换为MP3格式
                    if ("voice".equals(mediaType) && ".amr".equalsIgnoreCase(defaultExt)) {
                        String mp3Path = convertAmrToMp3(localPath.toString());
                        if (mp3Path != null) {
                            // 返回MP3文件路径
                            Path mp3FileName = Paths.get(mp3Path).getFileName();
                            String relativePath = "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + mp3FileName.toString();
                            System.out.println("语音转换成功: " + mp3Path + " -> 访问路径: " + relativePath);
                            // 尝试删除原始AMR文件（如果失败也不影响返回MP3路径）
                            try {
                                Files.deleteIfExists(localPath);
                            } catch (Exception e) {
                                System.out.println("删除AMR文件失败（不影响使用）: " + e.getMessage());
                            }
                            return relativePath;
                        }
                    }
                    
                    // 返回相对于静态资源根目录的路径
                    String relativePath = "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + fileName;
                    System.out.println(mediaType + "下载成功: " + localPath.toString() + " -> 访问路径: " + relativePath);
                    return relativePath;
                }
            } else {
                System.err.println("下载" + mediaType + "失败，HTTP状态码: " + responseCode);
                return null;
            }
        } catch (Exception e) {
            System.err.println("下载" + mediaType + "到本地时出错: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 从CQ码中提取URL（通用方法）
     */
    private String extractUrlFromCQ(String cqMessage) {
        try {
            // 尝试匹配 url=`...` 格式（带反引号）
            Pattern pattern = Pattern.compile("url=`([^`]+)`");
            Matcher matcher = pattern.matcher(cqMessage);
            if (matcher.find()) {
                String url = matcher.group(1);
                url = url.replace("&amp;", "&");
                return url;
            }
            
            // 匹配 url=xxx 格式（不带反引号）
            pattern = Pattern.compile("url=([^,\\]]+)");
            matcher = pattern.matcher(cqMessage);
            if (matcher.find()) {
                String url = matcher.group(1);
                url = url.replace("&amp;", "&");
                return url;
            }
        } catch (Exception e) {
            System.err.println("提取URL时出错: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 下载图片到本地存储（兼容旧方法）
     * @param cqMessage CQ码格式的图片消息
     * @param groupId 群号
     * @return 本地文件路径，下载失败返回null
     */
    private String downloadImageToLocal(String cqMessage, String groupId) {
        return downloadMediaToLocal(cqMessage, groupId, "images", ".jpg");
    }
    
    /**
     * 将AMR语音文件转换为MP3格式
     * @param amrPath AMR文件路径
     * @return MP3文件路径，转换失败返回null
     */
    private String convertAmrToMp3(String amrPath) {
        try {
            // 检查文件是否存在
            Path amrFile = Paths.get(amrPath);
            if (!Files.exists(amrFile)) {
                System.err.println("AMR文件不存在: " + amrPath);
                return null;
            }
            
            String mp3Path = amrPath.replace(".amr", ".mp3");
            System.out.println("开始转换AMR到MP3: " + amrPath + " -> " + mp3Path);
            
            // 使用项目内的FFmpeg进行转换（使用绝对路径）
            String ffmpegPath = "D:\\ai\\Documents\\qq-web\\ffmpeg-8.1-essentials_build\\bin\\ffmpeg.exe";
            
            System.out.println("FFmpeg路径: " + ffmpegPath);
            
            // 检查FFmpeg是否存在
            File ffmpegFile = new File(ffmpegPath);
            System.out.println("FFmpeg文件是否存在: " + ffmpegFile.exists());
            if (!ffmpegFile.exists()) {
                System.err.println("FFmpeg不存在: " + ffmpegPath);
                // 尝试使用系统PATH中的ffmpeg
                ffmpegPath = "ffmpeg";
                System.out.println("尝试使用系统PATH中的ffmpeg");
            } else {
                System.out.println("使用FFmpeg: " + ffmpegPath);
            }
            
            ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-i", amrPath,
                "-acodec", "libmp3lame",
                "-q:a", "2",
                "-y", // 覆盖已存在的文件
                mp3Path
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // 读取输出
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            System.out.println("FFmpeg输出: " + output.toString());
            
            if (exitCode == 0) {
                // 检查MP3文件是否生成成功
                Path mp3File = Paths.get(mp3Path);
                if (Files.exists(mp3File)) {
                    long mp3Size = Files.size(mp3File);
                    System.out.println("AMR转MP3成功: " + mp3Path + " (大小: " + mp3Size + " bytes)");
                    return mp3Path;
                } else {
                    System.err.println("AMR转MP3失败: MP3文件未生成");
                    return null;
                }
            } else {
                System.err.println("AMR转MP3失败，退出码: " + exitCode);
                System.err.println("FFmpeg错误输出: " + output.toString());
                return null;
            }
        } catch (Exception e) {
            System.err.println("AMR转MP3时出错: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 下载群头像到本地存储
     * @param groupId 群号
     * @return 本地文件路径，下载失败返回null
     */
    private String downloadGroupAvatarToLocal(String groupId) {
        try {
            // QQ群头像URL - 使用正确的群头像API
            // 格式: https://p.qlogo.cn/gh/{groupId}/{groupId}/100
            String imageUrl = "https://p.qlogo.cn/gh/" + groupId + "/" + groupId + "/100";
            
            // 创建本地存储目录
            Path avatarDir = Paths.get(localImagePath, "avatars");
            if (!Files.exists(avatarDir)) {
                Files.createDirectories(avatarDir);
            }
            
            // 生成文件名
            String fileName = "group_" + groupId + ".jpg";
            Path localPath = avatarDir.resolve(fileName);
            
            // 下载图片
            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(localPath.toFile())) {
                    
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    
                    // 返回相对于静态资源根目录的路径
                    String relativePath = "/images/avatars/" + fileName;
                    System.out.println("群头像下载成功: " + localPath.toString() + " -> 访问路径: " + relativePath);
                    return relativePath;
                }
            } else {
                System.err.println("下载群头像失败，HTTP状态码: " + responseCode + ", URL: " + imageUrl);
                return null;
            }
        } catch (Exception e) {
            System.err.println("下载群头像到本地时出错: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
