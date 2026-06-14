package com.qqai.repository;

import com.qqai.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    
    /**
     * 根据群聊ID查询消息（按时间倒序）
     */
    List<Message> findByGroupIdOrderBySendTimeDesc(String groupId);
    
    /**
     * 根据群聊ID查询未归档的消息
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.archived = false ORDER BY m.sendTime DESC")
    List<Message> findActiveMessagesByGroupId(@Param("groupId") String groupId);
    
    /**
     * 查询指定时间之前未归档的消息
     */
    List<Message> findBySendTimeBeforeAndArchivedFalse(LocalDateTime sendTime);
    
    /**
     * 查询指定时间之前已归档的消息
     */
    List<Message> findBySendTimeBeforeAndArchivedTrue(LocalDateTime sendTime);
    
    /**
     * 查询未处理的消息
     */
    List<Message> findByProcessedFalse();
    
    /**
     * 根据消息类型查询
     */
    List<Message> findByMessageType(Message.MessageType messageType);
    
    /**
     * 根据文件ID查询
     */
    List<Message> findByFileId(String fileId);
    
    /**
     * 统计群聊消息数量
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.groupId = :groupId AND m.archived = false")
    Long countActiveMessagesByGroupId(@Param("groupId") String groupId);
    
    /**
     * 分页查询消息
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.archived = false ORDER BY m.sendTime DESC")
    List<Message> findMessagesByGroupIdWithLimit(@Param("groupId") String groupId, org.springframework.data.domain.Pageable pageable);
    
    /**
     * 查询最近对话的群聊
     */
    @Query("SELECT m.groupId, MAX(m.groupName) as groupName FROM Message m WHERE m.archived = false GROUP BY m.groupId ORDER BY MAX(m.sendTime) DESC LIMIT 10")
    List<Object[]> findRecentGroups();
    
    // ==================== 按用户QQ过滤的查询方法 ====================
    
    /**
     * 根据群聊ID和登录者QQ查询消息（按时间倒序）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq = :selfQq ORDER BY m.sendTime DESC")
    List<Message> findByGroupIdAndSelfQqOrderBySendTimeDesc(@Param("groupId") String groupId, @Param("selfQq") String selfQq);
    
    /**
     * 根据群聊ID和登录者QQ查询未归档消息（分页）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq = :selfQq AND m.archived = false ORDER BY m.sendTime DESC")
    List<Message> findMessagesByGroupIdAndSelfQqWithLimit(@Param("groupId") String groupId, @Param("selfQq") String selfQq, org.springframework.data.domain.Pageable pageable);
    
    /**
     * 统计群聊消息数量（按登录者QQ过滤）
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.groupId = :groupId AND m.selfQq = :selfQq AND m.archived = false")
    Long countActiveMessagesByGroupIdAndSelfQq(@Param("groupId") String groupId, @Param("selfQq") String selfQq);
    
    /**
     * 查询用户有消息的群聊列表
     */
    @Query("SELECT DISTINCT m.groupId FROM Message m WHERE m.selfQq = :selfQq AND m.archived = false")
    List<String> findGroupIdsBySelfQq(@Param("selfQq") String selfQq);

    // ==================== 按多个用户QQ过滤的查询方法 ====================

    /**
     * 根据群聊ID和多个登录者QQ查询消息（按时间倒序）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq IN :selfQqList ORDER BY m.sendTime DESC")
    List<Message> findByGroupIdAndSelfQqInOrderBySendTimeDesc(@Param("groupId") String groupId, @Param("selfQqList") List<String> selfQqList);

    /**
     * 根据群聊ID和多个登录者QQ查询未归档消息（分页）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq IN :selfQqList AND m.archived = false ORDER BY m.sendTime DESC")
    List<Message> findMessagesByGroupIdAndSelfQqInWithLimit(@Param("groupId") String groupId, @Param("selfQqList") List<String> selfQqList, org.springframework.data.domain.Pageable pageable);

    /**
     * 统计群聊消息数量（按多个登录者QQ过滤）
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.groupId = :groupId AND m.selfQq IN :selfQqList AND m.archived = false")
    Long countActiveMessagesByGroupIdAndSelfQqIn(@Param("groupId") String groupId, @Param("selfQqList") List<String> selfQqList);

    /**
     * 根据消息ID判断消息是否存在
     */
    boolean existsByMessageId(String messageId);

    /**
     * 获取指定 ID 之后的新消息（增量拉取）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq IN :selfQqList AND m.archived = false AND m.id > :afterId ORDER BY m.sendTime ASC")
    List<Message> findNewMessagesAfterId(@Param("groupId") String groupId, @Param("selfQqList") List<String> selfQqList, @Param("afterId") Long afterId);

    /**
     * 统计指定时间范围内的消息数量
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.sendTime >= :start AND m.sendTime < :end")
    Long countBySendTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 按消息类型统计数量
     */
    @Query("SELECT m.messageType, COUNT(m) FROM Message m GROUP BY m.messageType")
    List<Object[]> countByMessageType();
}
