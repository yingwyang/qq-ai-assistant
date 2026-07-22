package com.qqai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.entity.AstrBotConversation;
import com.qqai.entity.AstrBotMessage;
import com.qqai.repository.AstrBotConversationRepository;
import com.qqai.repository.AstrBotMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AstrBot 对话服务
 * 管理对话会话和消息的存储、查询
 */
@Service
public class AstrBotConversationService {

    private static final Logger logger = LoggerFactory.getLogger(AstrBotConversationService.class);

    @Autowired
    private AstrBotConversationRepository conversationRepository;

    @Autowired
    private AstrBotMessageRepository messageRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 对话会话管理 ====================

    /**
     * 创建新对话
     */
    @Transactional
    public AstrBotConversation createConversation(Long userId, String groupId, String userQq, String userNickname, String title, String model) {
        AstrBotConversation conversation = new AstrBotConversation();
        conversation.setUserId(userId);
        conversation.setGroupId(groupId);
        conversation.setUserQq(userQq);
        conversation.setUserNickname(userNickname);
        conversation.setTitle(title != null ? title : "新对话");
        conversation.setModel(model);
        conversation.setMessageCount(0);
        conversation.setTotalTokens(0);
        conversation.setArchived(false);

        AstrBotConversation saved = conversationRepository.save(conversation);
        logger.info("创建新对话: conversationId={}, userId={}, groupId={}, userQq={}",
                saved.getConversationId(), userId, groupId, userQq);
        return saved;
    }

    /**
     * 获取对话信息
     */
    public Optional<AstrBotConversation> getConversation(String conversationId) {
        return conversationRepository.findByConversationId(conversationId);
    }

    /**
     * 获取或创建对话
     * 如果提供了 conversationId 则查找，否则创建新对话
     */
    @Transactional
    public AstrBotConversation getOrCreateConversation(String conversationId, Long userId, String groupId,
                                                        String userQq, String userNickname, String model) {
        if (conversationId != null && !conversationId.isEmpty()) {
            Optional<AstrBotConversation> existing = conversationRepository.findByConversationId(conversationId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return createConversation(userId, groupId, userQq, userNickname, null, model);
    }

    /**
     * 获取用户的对话列表
     */
    public List<AstrBotConversation> getUserConversations(String userQq) {
        return conversationRepository.findByUserQqOrderByTimeUpdatedDesc(userQq);
    }

    /**
     * 获取群聊的对话列表
     */
    public List<AstrBotConversation> getGroupConversations(String groupId) {
        return conversationRepository.findByGroupIdAndUserQqOrderByTimeUpdatedDesc(groupId, null);
    }

    /**
     * 获取所有未归档的对话
     */
    public List<AstrBotConversation> getAllActiveConversations() {
        return conversationRepository.findByArchivedFalseOrderByTimeUpdatedDesc();
    }

    /**
     * 获取群聊和用户的对话列表（分页）
     */
    public Page<AstrBotConversation> getConversations(String groupId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return conversationRepository.findByGroupIdOrderByTimeUpdatedDesc(groupId, pageable);
    }

    /**
     * 更新对话标题
     */
    @Transactional
    public void updateConversationTitle(String conversationId, String title) {
        Optional<AstrBotConversation> opt = conversationRepository.findByConversationId(conversationId);
        if (opt.isPresent()) {
            AstrBotConversation conversation = opt.get();
            conversation.setTitle(title);
            conversationRepository.save(conversation);
            logger.info("更新对话标题: conversationId={}, title={}", conversationId, title);
        }
    }

    /**
     * 归档对话
     */
    @Transactional
    public void archiveConversation(String conversationId) {
        Optional<AstrBotConversation> opt = conversationRepository.findByConversationId(conversationId);
        if (opt.isPresent()) {
            AstrBotConversation conversation = opt.get();
            conversation.setArchived(true);
            conversation.setTimeArchived(LocalDateTime.now());
            conversationRepository.save(conversation);
            logger.info("归档对话: conversationId={}", conversationId);
        }
    }

    /**
     * 删除对话及其所有消息
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        // 先删除所有消息
        int deletedMessages = messageRepository.deleteByConversationId(conversationId);
        // 再删除对话
        Optional<AstrBotConversation> opt = conversationRepository.findByConversationId(conversationId);
        opt.ifPresent(conversationRepository::delete);
        logger.info("删除对话: conversationId={}, 删除消息数={}", conversationId, deletedMessages);
    }

    // ==================== 消息管理 ====================

    /**
     * 添加用户消息
     */
    @Transactional
    public AstrBotMessage addUserMessage(String conversationId, String content, Map<String, Object> extraData) {
        return addMessage(conversationId, AstrBotMessage.MessageRole.USER, content, extraData, null, null, null, null);
    }

    /**
     * 添加AI回复消息
     */
    @Transactional
    public AstrBotMessage addAssistantMessage(String conversationId, String content, String model,
                                               Integer tokens, Integer promptTokens, Integer completionTokens) {
        return addMessage(conversationId, AstrBotMessage.MessageRole.ASSISTANT, content, null,
                model, tokens, promptTokens, completionTokens);
    }

    /**
     * 添加系统消息
     */
    @Transactional
    public AstrBotMessage addSystemMessage(String conversationId, String content) {
        return addMessage(conversationId, AstrBotMessage.MessageRole.SYSTEM, content, null, null, null, null, null);
    }

    /**
     * 添加消息（通用方法）
     */
    @Transactional
    public AstrBotMessage addMessage(String conversationId, AstrBotMessage.MessageRole role,
                                      String content, Map<String, Object> extraData,
                                      String model, Integer tokens, Integer promptTokens, Integer completionTokens) {
        // 验证对话存在
        Optional<AstrBotConversation> opt = conversationRepository.findByConversationId(conversationId);
        if (!opt.isPresent()) {
            throw new IllegalArgumentException("对话不存在: " + conversationId);
        }

        AstrBotMessage message = new AstrBotMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setModel(model);
        message.setTokens(tokens);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);

        // 将额外数据转为JSON
        if (extraData != null && !extraData.isEmpty()) {
            try {
                message.setData(objectMapper.writeValueAsString(extraData));
            } catch (JsonProcessingException e) {
                logger.warn("无法序列化额外数据: {}", e.getMessage());
            }
        }

        AstrBotMessage saved = messageRepository.save(message);

        // 更新对话统计
        updateConversationStats(conversationId);

        logger.debug("添加消息: messageId={}, conversationId={}, role={}",
                saved.getMessageId(), conversationId, role);
        return saved;
    }

    /**
     * 获取对话的所有消息
     */
    public List<AstrBotMessage> getConversationMessages(String conversationId) {
        return messageRepository.findByConversationIdOrderByTimeCreatedAsc(conversationId);
    }

    /**
     * 获取对话消息（分页）
     */
    public Page<AstrBotMessage> getConversationMessages(String conversationId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return messageRepository.findByConversationIdOrderByTimeCreatedAsc(conversationId, pageable);
    }

    /**
     * 获取最近的消息（用于构建上下文）
     */
    public List<AstrBotMessage> getRecentMessages(String conversationId, int limit) {
        List<AstrBotMessage> messages = messageRepository.findRecentMessagesByConversationId(conversationId, limit);
        // 复制到新列表后再反转，避免 JPA 返回不可修改列表导致 UnsupportedOperationException
        List<AstrBotMessage> result = new ArrayList<>(messages);
        Collections.reverse(result);
        return result;
    }

    /**
     * 获取消息的额外数据
     */
    public Map<String, Object> getMessageData(String messageId) {
        Optional<AstrBotMessage> opt = messageRepository.findByMessageId(messageId);
        if (opt.isPresent() && opt.get().getData() != null) {
            try {
                return objectMapper.readValue(opt.get().getData(), Map.class);
            } catch (JsonProcessingException e) {
                logger.warn("无法解析消息数据: {}", e.getMessage());
            }
        }
        return new HashMap<>();
    }

    /**
     * 删除单条消息
     */
    @Transactional
    public void deleteMessage(String messageId) {
        Optional<AstrBotMessage> opt = messageRepository.findByMessageId(messageId);
        if (opt.isPresent()) {
            String conversationId = opt.get().getConversationId();
            messageRepository.delete(opt.get());
            // 更新对话统计
            updateConversationStats(conversationId);
            logger.info("删除消息: messageId={}", messageId);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 更新对话统计信息
     */
    @Transactional
    public void updateConversationStats(String conversationId) {
        long messageCount = messageRepository.countByConversationId(conversationId);
        Integer totalTokens = messageRepository.sumTokensByConversationId(conversationId);

        conversationRepository.updateMessageCount(conversationId, (int) messageCount, LocalDateTime.now());
        conversationRepository.updateTotalTokens(conversationId, totalTokens != null ? totalTokens : 0, LocalDateTime.now());
    }

    /**
     * 构建对话上下文（用于发送给 AstrBot）
     */
    public List<Map<String, String>> buildConversationContext(String conversationId, int maxMessages) {
        List<AstrBotMessage> messages = getRecentMessages(conversationId, maxMessages);
        List<Map<String, String>> context = new ArrayList<>();

        for (AstrBotMessage msg : messages) {
            Map<String, String> messageMap = new HashMap<>();
            messageMap.put("role", msg.getRole().name().toLowerCase());
            messageMap.put("content", msg.getContent());
            context.add(messageMap);
        }

        return context;
    }

    /**
     * 自动生成对话标题（基于第一条用户消息）
     */
    @Transactional
    public void autoGenerateTitle(String conversationId) {
        List<AstrBotMessage> messages = messageRepository.findByConversationIdAndRoleOrderByTimeCreatedAsc(
                conversationId, AstrBotMessage.MessageRole.USER);

        if (!messages.isEmpty()) {
            String firstMessage = messages.get(0).getContent();
            String title = firstMessage.length() > 30 ?
                    firstMessage.substring(0, 30) + "..." : firstMessage;
            updateConversationTitle(conversationId, title);
        }
    }

    // ==================== 归档和清理 ====================

    /**
     * 归档旧对话
     */
    @Transactional
    public int archiveOldConversations(int daysBefore) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(daysBefore);
        List<AstrBotConversation> oldConversations = conversationRepository.findConversationsToArchive(beforeTime);

        if (!oldConversations.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (AstrBotConversation conv : oldConversations) {
                ids.add(conv.getId());
            }
            int archived = conversationRepository.archiveConversations(ids, LocalDateTime.now());
            logger.info("归档 {} 个旧对话", archived);
            return archived;
        }
        return 0;
    }

    /**
     * 清理已归档的旧对话
     */
    @Transactional
    public int cleanupArchivedConversations(int daysBefore) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(daysBefore);
        int deleted = conversationRepository.deleteOldArchivedConversations(beforeTime);
        logger.info("清理 {} 个已归档的旧对话", deleted);
        return deleted;
    }

    /**
     * 获取对话统计信息
     */
    public Map<String, Object> getConversationStats(String conversationId) {
        Map<String, Object> stats = new HashMap<>();

        long messageCount = messageRepository.countByConversationId(conversationId);
        Integer totalTokens = messageRepository.sumTokensByConversationId(conversationId);

        stats.put("messageCount", messageCount);
        stats.put("totalTokens", totalTokens != null ? totalTokens : 0);

        Optional<AstrBotMessage> lastMessage = messageRepository.findTopByConversationIdOrderByTimeCreatedDesc(conversationId);
        lastMessage.ifPresent(msg -> stats.put("lastMessageTime", msg.getTimeCreated()));

        return stats;
    }
}
