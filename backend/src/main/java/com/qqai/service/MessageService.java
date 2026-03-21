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
     */
    public List<Map<String, Object>> getRecentGroups(String userId) {
        List<Map<String, Object>> recentGroups = new ArrayList<>();
        
        // 如果提供了 userId，查询该用户的群聊；否则查询所有群聊
        List<Group> groups;
        if (userId != null && !userId.isEmpty()) {
            // 根据登录者QQ查询群聊，按加入时间倒序
            groups = groupRepository.findByOwnerQqAndActiveTrue(userId);
        } else {
            // 查询所有活跃群聊（包括owner_qq为空的旧数据）
            groups = groupRepository.findByActiveTrue();
        }
        
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
}
