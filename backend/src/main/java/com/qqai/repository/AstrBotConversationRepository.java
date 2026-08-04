package com.qqai.repository;

import com.qqai.entity.AstrBotConversation;
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
 * AstrBot 对话会话 Repository
 */
@Repository
public interface AstrBotConversationRepository extends JpaRepository<AstrBotConversation, Long> {

    /**
     * 根据对话ID查找
     */
    Optional<AstrBotConversation> findByConversationId(String conversationId);

    /**
     * 根据群号和用户QQ查找对话列表
     */
    List<AstrBotConversation> findByGroupIdAndUserQqOrderByTimeUpdatedDesc(String groupId, String userQq);

    /**
     * 根据群号查找对话列表（分页）
     */
    Page<AstrBotConversation> findByGroupIdOrderByTimeUpdatedDesc(String groupId, Pageable pageable);

    /**
     * 根据用户QQ查找对话列表
     */
    List<AstrBotConversation> findByUserQqOrderByTimeUpdatedDesc(String userQq);

    /**
     * 根据系统用户ID查找对话列表
     */
    List<AstrBotConversation> findByUserIdOrderByTimeUpdatedDesc(Long userId);

    /**
     * 根据系统用户ID和群号查找对话列表
     */
    List<AstrBotConversation> findByUserIdAndGroupIdOrderByTimeUpdatedDesc(Long userId, String groupId);

    /**
     * 查找未归档的对话
     */
    List<AstrBotConversation> findByArchivedFalseOrderByTimeUpdatedDesc();

    /**
     * 查找需要归档的旧对话
     */
    @Query("SELECT c FROM AstrBotConversation c WHERE c.archived = false AND c.timeUpdated < :beforeTime")
    List<AstrBotConversation> findConversationsToArchive(@Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 归档对话
     */
    @Modifying
    @Query("UPDATE AstrBotConversation c SET c.archived = true, c.timeArchived = :now WHERE c.id IN :ids")
    int archiveConversations(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * 更新消息计数
     */
    @Modifying
    @Query("UPDATE AstrBotConversation c SET c.messageCount = :count, c.timeUpdated = :now WHERE c.conversationId = :conversationId")
    int updateMessageCount(@Param("conversationId") String conversationId, @Param("count") Integer count, @Param("now") LocalDateTime now);

    /**
     * 更新Token计数
     */
    @Modifying
    @Query("UPDATE AstrBotConversation c SET c.totalTokens = :tokens, c.timeUpdated = :now WHERE c.conversationId = :conversationId")
    int updateTotalTokens(@Param("conversationId") String conversationId, @Param("tokens") Integer tokens, @Param("now") LocalDateTime now);

    /**
     * 删除旧的已归档对话
     */
    @Modifying
    @Query("DELETE FROM AstrBotConversation c WHERE c.archived = true AND c.timeArchived < :beforeTime")
    int deleteOldArchivedConversations(@Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 统计对话数量
     */
    long countByGroupId(String groupId);

    /**
     * 统计用户对话数量
     */
    long countByUserQq(String userQq);

    /**
     * 统计系统用户的对话数量
     */
    long countByUserId(Long userId);

    /**
     * 根据系统用户ID查找未归档的对话列表
     */
    List<AstrBotConversation> findByUserIdAndArchivedFalseOrderByTimeUpdatedDesc(Long userId);

    /**
     * 防御性包装 findByUserIdOrderByTimeUpdatedDesc
     */
    default List<AstrBotConversation> safeFindByUserIdOrderByTimeUpdatedDesc(Long userId) {
        if (userId == null) return java.util.Collections.emptyList();
        List<AstrBotConversation> result = findByUserIdOrderByTimeUpdatedDesc(userId);
        return result != null ? result : java.util.Collections.emptyList();
    }

    /**
     * 防御性包装 countByUserId
     */
    default long safeCountByUserId(Long userId) {
        if (userId == null) return 0L;
        return countByUserId(userId);
    }
}
