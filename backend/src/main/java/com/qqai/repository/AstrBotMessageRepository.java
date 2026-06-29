package com.qqai.repository;

import com.qqai.entity.AstrBotMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AstrBot 对话消息 Repository
 */
@Repository
public interface AstrBotMessageRepository extends JpaRepository<AstrBotMessage, Long> {

    /**
     * 根据消息ID查找
     */
    Optional<AstrBotMessage> findByMessageId(String messageId);

    /**
     * 根据对话ID查找所有消息（按时间排序）
     */
    List<AstrBotMessage> findByConversationIdOrderByTimeCreatedAsc(String conversationId);

    /**
     * 根据对话ID查找消息（分页）
     */
    Page<AstrBotMessage> findByConversationIdOrderByTimeCreatedAsc(String conversationId, Pageable pageable);

    /**
     * 查找对话中的最后一条消息
     */
    Optional<AstrBotMessage> findTopByConversationIdOrderByTimeCreatedDesc(String conversationId);

    /**
     * 统计对话的消息数量
     */
    long countByConversationId(String conversationId);

    /**
     * 统计对话的Token总数
     */
    @Query("SELECT COALESCE(SUM(m.tokens), 0) FROM AstrBotMessage m WHERE m.conversationId = :conversationId")
    Integer sumTokensByConversationId(@Param("conversationId") String conversationId);

    /**
     * 根据角色查找消息
     */
    List<AstrBotMessage> findByConversationIdAndRoleOrderByTimeCreatedAsc(
            String conversationId, AstrBotMessage.MessageRole role);

    /**
     * 删除对话的所有消息
     */
    @Modifying
    @Query("DELETE FROM AstrBotMessage m WHERE m.conversationId = :conversationId")
    int deleteByConversationId(@Param("conversationId") String conversationId);

    /**
     * 批量删除消息
     */
    @Modifying
    @Query("DELETE FROM AstrBotMessage m WHERE m.id IN :ids")
    int deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 查找旧消息（用于清理）
     */
    @Query("SELECT m FROM AstrBotMessage m WHERE m.timeCreated < :beforeTime")
    List<AstrBotMessage> findOldMessages(@Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 查找某段时间内的消息
     */
    @Query("SELECT m FROM AstrBotMessage m WHERE m.conversationId = :conversationId AND m.timeCreated BETWEEN :startTime AND :endTime ORDER BY m.timeCreated ASC")
    List<AstrBotMessage> findByConversationIdAndTimeRange(
            @Param("conversationId") String conversationId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 获取对话的消息列表（限制数量，用于构建上下文）
     * 使用 Pageable 实现限制
     */
    default List<AstrBotMessage> findRecentMessagesByConversationId(String conversationId, int limit) {
        return findByConversationIdOrderByTimeCreatedAsc(conversationId, 
                org.springframework.data.domain.PageRequest.of(0, limit))
                .getContent();
    }
}
