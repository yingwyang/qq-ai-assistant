package com.qqai.service;

import com.qqai.entity.Message;
import com.qqai.entity.Group;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private com.qqai.repository.GroupReadStateRepository groupReadStateRepository;

    @Autowired
    private AstrBotService astrBotService;

    @Autowired
    private MessageArchiveService messageArchiveService;

    @Autowired
    private MessageBroadcastService messageBroadcastService;

    @Autowired
    private NapCatService napCatService;

    public Message saveMessage(Message message) {
        if (message.getSendTime() == null) {
            message.setSendTime(LocalDateTime.now());
        }
        if (message.getServerRecvMs() == null) {
            message.setServerRecvMs(System.currentTimeMillis());
        }
        message.setProcessed(false);
        message.setArchived(false);
        Message savedMessage = messageRepository.save(message);
        messageBroadcastService.broadcastNewMessage(savedMessage);
        processMessageAsync(savedMessage.getId());
        return savedMessage;
    }

    public boolean existsByMessageId(String messageId) {
        return messageRepository.existsByMessageId(messageId);
    }

    @Async
    public void processMessageAsync(Long messageId) {
        Optional<Message> messageOpt = messageRepository.findById(messageId);
        if (messageOpt.isEmpty()) {
            return;
        }
        Message message = messageOpt.get();
        try {
            String summary = astrBotService.summarizeMessage(message.getContent());
            message.setAiSummary(summary);
            message.setProcessed(true);
            Message updated = messageRepository.save(message);
            messageBroadcastService.broadcastMessageUpdate(updated);
        } catch (Exception e) {
            log.error("异步处理消息失败, id={}: {}", messageId, e.getMessage());
        }
    }

    public void processMessage(Message message) {
        processMessageAsync(message.getId());
    }

    public List<Message> getMessagesByGroupId(String groupId) {
        return messageRepository.findByGroupIdOrderBySendTimeDesc(groupId);
    }

    public List<Message> getMessagesByGroupIdPaged(String groupId, Pageable pageable) {
        return messageRepository.findMessagesByGroupIdWithLimit(groupId, pageable);
    }

    public Long countMessagesByGroupId(String groupId) {
        return messageRepository.countActiveMessagesByGroupId(groupId);
    }

    public List<Message> getMessagesByGroupIdAndUser(String groupId, String selfQq) {
        return messageRepository.findByGroupIdAndSelfQqOrderBySendTimeDesc(groupId, selfQq);
    }

    public List<Message> getMessagesByGroupIdPagedAndUser(String groupId, String selfQq, Pageable pageable) {
        return messageRepository.findMessagesByGroupIdAndSelfQqWithLimit(groupId, selfQq, pageable);
    }

    public Long countMessagesByGroupIdAndUser(String groupId, String selfQq) {
        return messageRepository.countActiveMessagesByGroupIdAndSelfQq(groupId, selfQq);
    }

    public List<String> getUserGroupIds(String selfQq) {
        return messageRepository.findGroupIdsBySelfQq(selfQq);
    }

    public List<Message> getUnprocessedMessages() {
        return messageRepository.findByProcessedFalse();
    }

    public void processAllUnprocessedMessages() {
        List<Message> unprocessedMessages = getUnprocessedMessages();
        for (Message message : unprocessedMessages) {
            processMessage(message);
        }
    }

    public void manualArchive(int daysBefore) {
        messageArchiveService.manualArchive(daysBefore);
    }

    public List<Message> getMessagesSinceId(String groupId, List<String> selfQqList, Long afterId) {
        return messageRepository.findNewMessagesAfterId(groupId, selfQqList, afterId);
    }

    /**
     * 获取群聊成员 QQ 号与昵称映射（优先从 NapCat 获取完整群成员，再叠加本地消息发送者）。
     * 用于 @消息 解析：即使某个成员还没发过消息，也能通过群成员列表拿到昵称。
     */
    public List<Map<String, Object>> getGroupMemberNicknames(String groupId) {
        // 使用 LinkedHashMap 保持 NapCat 返回的成员顺序，并去重
        Map<String, String> nicknameMap = new LinkedHashMap<>();

        // 先收集本地消息里出现过的所有 QQ 号（后续用于兜底单独查询）
        List<Object[]> rows = messageRepository.findDistinctSendersByGroupId(groupId);
        Set<String> localQqs = new HashSet<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            String qq = String.valueOf(row[0]);
            localQqs.add(qq);
            String nickname = row[1] != null ? String.valueOf(row[1]) : null;
            if (nickname != null && !nickname.isBlank()) {
                nicknameMap.put(qq, nickname);
            }
        }

        // 2. 从消息内容中的 [CQ:at,qq=xxx] 提取被 @ 的 QQ 号，也可能没发过消息
        try {
            List<String> atContents = messageRepository.findAtContentsByGroupId(groupId);
            Pattern atPattern = Pattern.compile("\\[CQ:at,qq=([^,\\]]+)\\]");
            for (String content : atContents) {
                if (content == null) continue;
                Matcher matcher = atPattern.matcher(content);
                while (matcher.find()) {
                    localQqs.add(matcher.group(1));
                }
            }
        } catch (Exception e) {
            System.err.println("提取 @目标 QQ 失败: " + e.getMessage());
        }

        // 3. 优先从 NapCat 拉取完整群成员列表
        try {
            JSONArray members = napCatService.getGroupMemberList(groupId, true);
            if (members != null) {
                for (int i = 0; i < members.size(); i++) {
                    JSONObject member = members.getJSONObject(i);
                    if (member == null) continue;
                    Object userIdObj = member.get("user_id");
                    if (userIdObj == null) userIdObj = member.get("userId");
                    if (userIdObj == null) continue;
                    String qq = String.valueOf(userIdObj);
                    // 优先使用群名片(card)，其次昵称(nickname)
                    String nickname = member.getString("card");
                    if (nickname == null || nickname.isBlank()) {
                        nickname = member.getString("nickname");
                    }
                    if (nickname != null && !nickname.isBlank()) {
                        nicknameMap.put(qq, nickname);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("从 NapCat 加载群成员失败，回退到本地消息发送者: " + e.getMessage());
        }

        // 4. 兜底：对本地消息中出现但列表里仍缺失的 QQ 单独调用 get_group_member_info
        // 这通常用于机器人自己或被 @ 但还没发过消息的成员
        for (String qq : localQqs) {
            if (nicknameMap.containsKey(qq) && nicknameMap.get(qq) != null && !nicknameMap.get(qq).isBlank()) {
                continue;
            }
            try {
                JSONObject member = napCatService.getGroupMemberInfo(groupId, qq);
                if (member != null) {
                    Object userIdObj = member.get("user_id");
                    if (userIdObj == null) userIdObj = member.get("userId");
                    if (userIdObj == null) continue;
                    String returnedQq = String.valueOf(userIdObj);
                    String nickname = member.getString("card");
                    if (nickname == null || nickname.isBlank()) {
                        nickname = member.getString("nickname");
                    }
                    if (nickname != null && !nickname.isBlank()) {
                        nicknameMap.put(returnedQq, nickname);
                    }
                }
            } catch (Exception e) {
                System.err.println("兜底查询群成员信息失败 (qq=" + qq + "): " + e.getMessage());
            }
        }

        System.out.println("群" + groupId + "昵称映射数量: " + nicknameMap.size());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : nicknameMap.entrySet()) {
            Map<String, Object> map = new HashMap<>();
            map.put("qq", entry.getKey());
            map.put("nickname", entry.getValue());
            result.add(map);
        }
        return result;
    }

    /**
     * 已弃用：基于 QQ 号查询的旧版接口（保留用于兼容旧代码）。
     * 建议使用 getRecentGroups(Long userId, List<String> ownerQqList)。
     */
    @Deprecated
    public List<Map<String, Object>> getRecentGroups(String ownerQq) {
        if (ownerQq == null || ownerQq.isEmpty()) {
            return new ArrayList<>();
        }
        // 走老逻辑：直接把 owner_qq 找到 chat_groups 全部消息。
        List<Group> groups = groupRepository.findByOwnerQqAndActiveTrue(ownerQq);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Group g : groups) {
            Map<String, Object> map = new HashMap<>();
            map.put("groupId", g.getGroupId());
            map.put("groupName", g.getGroupName() != null ? g.getGroupName() : "群聊 " + g.getGroupId());
            map.put("ownerQq", ownerQq);
            map.put("avatar", g.getAvatar() != null ? g.getAvatar()
                    : "https://q.qlogo.cn/headimg_dl?dst_uin=" + g.getGroupId() + "&spec=100");
            map.put("unreadCount", 0L);
            Long lastRecvMs = messageRepository.findLastMessageTime(g.getGroupId());
            LocalDateTime t = epochMsToLocalDateTime(lastRecvMs);
            map.put("lastMessageTime", t != null ? t.toString()
                    : g.getJoinedTime() != null ? g.getJoinedTime().toString() : null);
            result.add(map);
        }
        result.sort((g1, g2) -> {
            String t1 = (String) g1.get("lastMessageTime");
            String t2 = (String) g2.get("lastMessageTime");
            if (t1 == null) return 1;
            if (t2 == null) return -1;
            return t2.compareTo(t1);
        });
        return result;
    }

    /**
     * 当前用户的所有群聊列表 + 未读消息数。
     *
     * @param userId       系统用户 ID（users.id）
     * @param ownerQqList  该用户绑定的 QQ 号集合（对应 self_qq / owner_qq）
     * @return 群聊列表，每个项包含：groupId/groupName/ownerQq/avatar/unreadCount/lastMessageTime
     */
    public List<Map<String, Object>> getRecentGroupsForUser(Long userId, List<String> ownerQqList) {
        List<Map<String, Object>> allGroups = new ArrayList<>();
        if (userId == null || ownerQqList == null || ownerQqList.isEmpty()) {
            return allGroups;
        }

        // 1. 找到用户所有绑定 QQ 号下的群聊
        List<Group> groups = new ArrayList<>();
        for (String qq : ownerQqList) {
            List<Group> qqGroups = groupRepository.findByOwnerQqAndActiveTrue(qq);
            if (qqGroups != null) {
                groups.addAll(qqGroups);
            }
        }
        if (groups.isEmpty()) {
            return allGroups;
        }

        // 2. 批量查用户对这些群聊的 lastReadTime
        List<String> groupIds = new ArrayList<>();
        for (Group g : groups) groupIds.add(g.getGroupId());
        Map<String, LocalDateTime> readTimeMap = new HashMap<>();
        try {
            List<com.qqai.entity.GroupReadState> readStates =
                    groupReadStateRepository.findByUserIdAndGroupIdIn(userId, groupIds);
            if (readStates != null) {
                for (com.qqai.entity.GroupReadState rs : readStates) {
                    readTimeMap.put(rs.getGroupId(), rs.getLastReadTime());
                }
            }
        } catch (Exception e) {
            log.warn("查询 group_read_state 失败: {}", e.getMessage());
        }

        // 3. 计算每个群的未读数
        for (Group group : groups) {
            Map<String, Object> groupMap = new HashMap<>();
            groupMap.put("groupId", group.getGroupId());
            groupMap.put("groupName",
                    group.getGroupName() != null ? group.getGroupName() : "群聊 " + group.getGroupId());
            groupMap.put("ownerQq", group.getOwnerQq() != null ? group.getOwnerQq() : "");
            groupMap.put("avatar",
                    group.getAvatar() != null ? group.getAvatar()
                            : "https://q.qlogo.cn/headimg_dl?dst_uin=" + group.getGroupId() + "&spec=100");

            LocalDateTime lastRead = readTimeMap.get(group.getGroupId());
            Long unreadCount;
            if (lastRead != null) {
                Long sinceMs = localDateTimeToEpochMs(lastRead);
                unreadCount = sinceMs != null
                        ? messageRepository.countUnreadMessagesSince(group.getGroupId(), sinceMs)
                        : 0L;
            } else {
                // 首次进入系统的用户：默认未读 = 0，避免显示一堆老消息未读
                unreadCount = 0L;
            }
            groupMap.put("unreadCount", unreadCount);

            Long lastRecvMs = messageRepository.findLastMessageTime(group.getGroupId());
            LocalDateTime lastMessageTime = epochMsToLocalDateTime(lastRecvMs);
            groupMap.put("lastMessageTime",
                    lastMessageTime != null ? lastMessageTime.toString()
                            : group.getJoinedTime() != null ? group.getJoinedTime().toString() : null);

            allGroups.add(groupMap);
        }

        // 4. 按最新消息时间倒序
        allGroups.sort((g1, g2) -> {
            String t1 = (String) g1.get("lastMessageTime");
            String t2 = (String) g2.get("lastMessageTime");
            if (t1 == null) return 1;
            if (t2 == null) return -1;
            return t2.compareTo(t1);
        });

        return allGroups;
    }

    public List<Message> getMessagesByGroupIdAndUserQqList(String groupId, List<String> selfQqList) {
        return messageRepository.findByGroupIdAndSelfQqInOrderBySendTimeDesc(groupId, selfQqList);
    }

    public List<Message> getMessagesByGroupIdPagedAndUserQqList(String groupId, List<String> selfQqList, Pageable pageable) {
        return messageRepository.findMessagesByGroupIdAndSelfQqInWithLimit(groupId, selfQqList, pageable);
    }

    public Long countMessagesByGroupIdAndUserQqList(String groupId, List<String> selfQqList) {
        return messageRepository.countActiveMessagesByGroupIdAndSelfQqIn(groupId, selfQqList);
    }

    public Optional<Message> getMessageById(Long id) {
        return messageRepository.findById(id);
    }

    /**
     * 单条删除消息（软删除：deleted=true，deletedAt=NOW，记录删除者）
     * 保留 archived 原值（后台归档与用户删除两套独立语义）。
     */
    @Transactional
    public void deleteMessage(Long id, Long userId) {
        Optional<Message> messageOpt = messageRepository.findById(id);
        if (messageOpt.isPresent()) {
            Message message = messageOpt.get();
            message.setDeleted(true);
            message.setDeletedAt(java.time.LocalDateTime.now());
            message.setDeletedBy(userId);
            messageRepository.save(message);
            messageBroadcastService.broadcastMessageUpdate(message);
            log.info("user {} deleted message {}", userId, id);
        }
    }

    /**
     * 批量删除消息（软删除），并可选同时删除关联的本地文件。
     *
     * @param ids          目标消息 ID 列表
     * @param selfQqList   当前用户绑定的 QQ 号列表（用于权限校验）
     * @param userId       当前用户 ID（写入 deletedBy）
     * @param deleteMedia  true 时同时删除 IMAGE / VIDEO / AUDIO / VOICE 类型消息的本地文件
     * @return 实际删除成功的消息数量
     */
    @Transactional
    public int deleteMessagesByIds(List<Long> ids, List<String> selfQqList, Long userId, boolean deleteMedia) {
        List<Message> messages = messageRepository.findAllById(ids);
        int deletedCount = 0;
        for (Message message : messages) {
            // 权限校验：只能删除自己绑定 QQ 接收的消息
            if (message.getSelfQq() == null || !selfQqList.contains(message.getSelfQq())) {
                continue;
            }
            if (message.isDeleted()) {
                continue; // 已删除的跳过
            }
            message.setDeleted(true);
            message.setDeletedAt(java.time.LocalDateTime.now());
            message.setDeletedBy(userId);
            messageRepository.save(message);
            messageBroadcastService.broadcastMessageUpdate(message);
            deletedCount++;

            // 删除关联的本地文件（按消息类型判断）
            if (deleteMedia) {
                Message.MessageType type = message.getMessageType();
                if (type != null
                        && (type == Message.MessageType.IMAGE
                            || type == Message.MessageType.VIDEO
                            || type == Message.MessageType.AUDIO
                            || type == Message.MessageType.VOICE)) {
                    try {
                        File media = extractFileFromMessage(message);
                        if (media != null && media.exists()) {
                            if (media.delete()) {
                                log.info("user {} deleted media file {}", userId, media.getAbsolutePath());
                            } else {
                                log.warn("failed to delete media file {}", media.getAbsolutePath());
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("extract file from message {} failed: {}", message.getId(), ex.getMessage());
                    }
                }
            }
        }
        if (deletedCount > 0) {
            log.info("user {} deleted {} messages (media={})", userId, deletedCount, deleteMedia);
        }
        return deletedCount;
    }

    /**
     * 从消息 content / fileId 中尝试解析本地文件（以 /images/ 开头的相对路径或绝对路径）。
     * 找不到本地文件时返回 null（避免上层代码 NPE）。
     */
    private File extractFileFromMessage(Message message) {
        String content = message.getContent();
        if (content != null && content.startsWith("/images/")) {
            String relative = content.startsWith("/") ? content.substring(1) : content;
            java.nio.file.Path path = java.nio.file.Paths.get(System.getProperty("user.dir"), "backend", relative);
            return path.toFile();
        }
        return null;
    }

    /**
     * 将某群聊标记为已读（upsert group_read_state 的 lastReadTime）
     */
    @Transactional
    public boolean markGroupAsRead(Long userId, String groupId) {
        if (userId == null || groupId == null || groupId.isEmpty()) return false;
        try {
            groupReadStateRepository.upsertReadState(userId, groupId, LocalDateTime.now());
            return true;
        } catch (Exception e) {
            log.warn("标记群聊 {} 为已读失败: {}", groupId, e.getMessage());
            return false;
        }
    }

    /**
     * 将某个用户所有群聊标为已读（批量更新 lastReadTime）
     */
    @Transactional
    public boolean markAllAsRead(Long userId) {
        if (userId == null) return false;
        try {
            groupReadStateRepository.markAllAsRead(userId, LocalDateTime.now());
            return true;
        } catch (Exception e) {
            log.warn("将用户 {} 全部群聊标为已读失败: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 毫秒级 epoch 转换为东八区 LocalDateTime（用于展示）。
     */
    private LocalDateTime epochMsToLocalDateTime(Long epochMs) {
        if (epochMs == null) {
            return null;
        }
        return Instant.ofEpochMilli(epochMs).atZone(ZONE_SHANGHAI).toLocalDateTime();
    }

    /**
     * 东八区 LocalDateTime 转换为毫秒级 epoch（用于和 serverRecvMs 比较）。
     */
    private Long localDateTimeToEpochMs(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(ZONE_SHANGHAI).toInstant().toEpochMilli();
    }
}
