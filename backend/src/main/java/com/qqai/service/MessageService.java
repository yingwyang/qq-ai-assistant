package com.qqai.service;

import com.qqai.entity.Group;
import com.qqai.entity.Message;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MessageService {
    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private AstrBotService astrBotService;

    @Autowired
    private MessageArchiveService messageArchiveService;

    public Message saveMessage(Message message) {
        if (message.getSendTime() == null) {
            message.setSendTime(LocalDateTime.now());
        }
        message.setProcessed(false);
        message.setArchived(false);
        Message savedMessage = messageRepository.save(message);
        // 异步处理消息
        processMessage(savedMessage);
        return savedMessage;
    }

    /**
     * 检查消息是否已存在（去重）
     */
    public boolean existsByMessageId(String messageId) {
        return messageRepository.existsByMessageId(messageId);
    }

    public void processMessage(Message message) {
        try {
            String summary = astrBotService.summarizeMessage(message.getContent());
            message.setAiSummary(summary);
            message.setProcessed(true);
            messageRepository.save(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
    
    // ==================== 按用户QQ过滤的查询方法 ====================
    
    public List<Message> getMessagesByGroupIdAndUser(String groupId, String selfQq) {
        return messageRepository.findByGroupIdAndSelfQqOrderBySendTimeDesc(groupId, selfQq);
    }

    public List<Message> getMessagesByGroupIdPagedAndUser(String groupId, String selfQq, Pageable pageable) {
        return messageRepository.findMessagesByGroupIdAndSelfQqWithLimit(groupId, selfQq, pageable);
    }

    public Long countMessagesByGroupIdAndUser(String groupId, String selfQq) {
        return messageRepository.countActiveMessagesByGroupIdAndSelfQq(groupId, selfQq);
    }
    
    /**
     * 获取用户有消息的群聊列表
     */
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

    /**
     * 手动触发归档
     */
    public void manualArchive(int daysBefore) {
        messageArchiveService.manualArchive(daysBefore);
    }

    /**
     * 获取最近对话的群聊
     * 从 chat_groups 表中获取指定登录者的群聊列表
     * 如果提供了 userId，只返回该用户有权限查看的群聊
     */
    public List<Map<String, Object>> getRecentGroups(String userId) {
        List<Map<String, Object>> recentGroups = new ArrayList<>();
        
        // 必须提供 userId，否则返回空列表
        if (userId == null || userId.isEmpty()) {
            return recentGroups;
        }
        
        // 根据登录者QQ查询群聊，按加入时间倒序
        List<Group> groups = groupRepository.findByOwnerQqAndActiveTrue(userId);
        
        // 按加入时间倒序排列
        groups.sort((g1, g2) -> {
            if (g1.getJoinedTime() == null) return 1;
            if (g2.getJoinedTime() == null) return -1;
            return g2.getJoinedTime().compareTo(g1.getJoinedTime());
        });
        
        for (Group group : groups) {
            Map<String, Object> groupMap = new HashMap<>();
            groupMap.put("groupId", group.getGroupId());
            groupMap.put("groupName", group.getGroupName() != null ? group.getGroupName() : "群聊 " + group.getGroupId());
            groupMap.put("ownerQq", group.getOwnerQq() != null ? group.getOwnerQq() : "");  // 返回登录者QQ
            
            // 使用本地存储的头像
            if (group.getAvatar() != null) {
                groupMap.put("avatar", group.getAvatar());
            } else {
                // 使用QQ群头像API
                groupMap.put("avatar", "https://q.qlogo.cn/headimg_dl?dst_uin=" + group.getGroupId() + "&spec=100");
            }
            
            recentGroups.add(groupMap);
        }
        
        return recentGroups;
    }

    // ==================== 按多个用户QQ过滤的查询方法 ====================

    /**
     * 根据群ID和多个QQ号查询消息
     */
    public List<Message> getMessagesByGroupIdAndUserQqList(String groupId, List<String> selfQqList) {
        return messageRepository.findByGroupIdAndSelfQqInOrderBySendTimeDesc(groupId, selfQqList);
    }

    /**
     * 分页查询群消息（按多个QQ号过滤）
     */
    public List<Message> getMessagesByGroupIdPagedAndUserQqList(String groupId, List<String> selfQqList, Pageable pageable) {
        return messageRepository.findMessagesByGroupIdAndSelfQqInWithLimit(groupId, selfQqList, pageable);
    }

    /**
     * 统计群消息数量（按多个QQ号过滤）
     */
    public Long countMessagesByGroupIdAndUserQqList(String groupId, List<String> selfQqList) {
        return messageRepository.countActiveMessagesByGroupIdAndSelfQqIn(groupId, selfQqList);
    }
}
