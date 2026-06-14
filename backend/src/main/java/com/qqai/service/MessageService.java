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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private AstrBotService astrBotService;

    @Autowired
    private MessageArchiveService messageArchiveService;

    @Autowired
    private MessageBroadcastService messageBroadcastService;

    public Message saveMessage(Message message) {
        if (message.getSendTime() == null) {
            message.setSendTime(LocalDateTime.now());
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

    public List<Map<String, Object>> getRecentGroups(String userId) {
        List<Map<String, Object>> recentGroups = new ArrayList<>();

        if (userId == null || userId.isEmpty()) {
            return recentGroups;
        }

        List<Group> groups = groupRepository.findByOwnerQqAndActiveTrue(userId);
        groups.sort((g1, g2) -> {
            if (g1.getJoinedTime() == null) return 1;
            if (g2.getJoinedTime() == null) return -1;
            return g2.getJoinedTime().compareTo(g1.getJoinedTime());
        });

        for (Group group : groups) {
            Map<String, Object> groupMap = new HashMap<>();
            groupMap.put("groupId", group.getGroupId());
            groupMap.put("groupName", group.getGroupName() != null ? group.getGroupName() : "群聊 " + group.getGroupId());
            groupMap.put("ownerQq", group.getOwnerQq() != null ? group.getOwnerQq() : "");

            if (group.getAvatar() != null) {
                groupMap.put("avatar", group.getAvatar());
            } else {
                groupMap.put("avatar", "https://q.qlogo.cn/headimg_dl?dst_uin=" + group.getGroupId() + "&spec=100");
            }

            recentGroups.add(groupMap);
        }

        return recentGroups;
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
}
