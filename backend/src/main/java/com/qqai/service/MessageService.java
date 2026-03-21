package com.qqai.service;

import com.qqai.entity.Group;
import com.qqai.entity.Message;
import com.qqai.entity.UserGroup;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.MessageRepository;
import com.qqai.repository.UserGroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessageService {
    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserGroupRepository userGroupRepository;

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
        List<Message> messages = messageRepository.findByGroupIdOrderBySendTimeDesc(groupId);
        
        // 从消息中提取用户和群聊的关联关系，并存储到 user_groups 表
        saveUserGroupRelationsFromMessages(messages);
        
        return messages;
    }
    
    /**
     * 从消息中提取并保存用户-群聊关联关系
     */
    private void saveUserGroupRelationsFromMessages(List<Message> messages) {
        for (Message message : messages) {
            String userId = message.getUserQq();
            String groupId = message.getGroupId();
            
            if (userId == null || userId.isEmpty() || groupId == null || groupId.isEmpty()) {
                continue;
            }
            
            // 检查是否已存在关联关系
            if (!userGroupRepository.existsByUserIdAndGroupId(userId, groupId)) {
                // 创建新的关联关系
                UserGroup userGroup = new UserGroup();
                userGroup.setUserId(userId);
                userGroup.setGroupId(groupId);
                userGroup.setActive(true);
                userGroupRepository.save(userGroup);
                System.out.println("保存用户-群聊关联: 用户=" + userId + ", 群聊=" + groupId);
            }
        }
    }

    public List<Message> getMessagesByGroupIdPaged(String groupId, Pageable pageable) {
        return messageRepository.findMessagesByGroupIdWithLimit(groupId, pageable);
    }

    public Long countMessagesByGroupId(String groupId) {
        return messageRepository.countActiveMessagesByGroupId(groupId);
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
     */
    public List<Map<String, Object>> getRecentGroups(String userId) {
        List<Map<String, Object>> recentGroups = new ArrayList<>();
        
        // 从UserGroup表获取用户的活跃群聊
        // 如果没有传入userId，使用默认值
        String currentUserQq = userId != null ? userId : "3649735451"; // 暂时使用默认值
        List<UserGroup> userGroups = userGroupRepository.findActiveGroupsByUserId(currentUserQq);
        
        for (UserGroup userGroup : userGroups) {
            // 从Group表获取群聊信息
            String groupId = userGroup.getGroupId();
            Group groupEntity = groupRepository.findByGroupId(groupId).orElse(null);
            
            Map<String, Object> group = new HashMap<>();
            group.put("groupId", groupId);
            
            if (groupEntity != null) {
                group.put("groupName", groupEntity.getGroupName() != null ? groupEntity.getGroupName() : "群聊 " + groupId);
                group.put("avatar", groupEntity.getAvatar() != null ? groupEntity.getAvatar() : "https://q.qlogo.cn/headimg_dl?dst_uin=" + groupId + "&spec=100");
            } else {
                // 如果Group表中没有数据，使用默认值
                group.put("groupName", "群聊 " + groupId);
                group.put("avatar", "https://q.qlogo.cn/headimg_dl?dst_uin=" + groupId + "&spec=100");
            }
            
            recentGroups.add(group);
        }
        
        // 如果UserGroup中没有数据，从Message表获取最近有消息的群聊
        if (recentGroups.isEmpty()) {
            List<Object[]> groupData = messageRepository.findRecentGroups();
            
            for (Object[] data : groupData) {
                Map<String, Object> group = new HashMap<>();
                group.put("groupId", data[0]);
                group.put("groupName", data[1] != null ? data[1] : "群聊 " + data[0]);
                // 使用QQ群头像API
                group.put("avatar", "https://q.qlogo.cn/headimg_dl?dst_uin=" + data[0] + "&spec=100");
                recentGroups.add(group);
            }
        }
        
        return recentGroups;
    }
}
