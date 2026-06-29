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
     * 根据群聊ID查询消息（按服务端接收时间倒序）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId ORDER BY m.serverRecvMs DESC, m.id DESC")
    List<Message> findByGroupIdOrderBySendTimeDesc(@Param("groupId") String groupId);
    
    /**
     * 根据群聊ID查询未归档&未被用户删除的消息（按服务端接收时间倒序）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.archived = false AND m.deleted = false ORDER BY m.serverRecvMs DESC, m.id DESC")
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
     * 统计群聊消息数量（未归档&未被用户删除）
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.groupId = :groupId AND m.archived = false AND m.deleted = false")
    Long countActiveMessagesByGroupId(@Param("groupId") String groupId);

    /**
     * 分页查询消息（未归档&未被用户删除）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.archived = false AND m.deleted = false ORDER BY m.serverRecvMs DESC, m.id DESC")
    List<Message> findMessagesByGroupIdWithLimit(@Param("groupId") String groupId, org.springframework.data.domain.Pageable pageable);

    /**
     * 查询最近对话的群聊（未归档&未被用户删除）
     */
    @Query("SELECT m.groupId, MAX(m.groupName) as groupName FROM Message m WHERE m.archived = false AND m.deleted = false GROUP BY m.groupId ORDER BY MAX(m.serverRecvMs) DESC LIMIT 10")
    List<Object[]> findRecentGroups();

    // ==================== 按用户QQ过滤的查询方法 ====================

    /**
     * 根据群聊ID和登录者QQ查询消息（按服务端接收时间倒序）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq = :selfQq ORDER BY m.serverRecvMs DESC, m.id DESC")
    List<Message> findByGroupIdAndSelfQqOrderBySendTimeDesc(@Param("groupId") String groupId, @Param("selfQq") String selfQq);

    /**
     * 根据群聊ID和登录者QQ查询未归档&未被用户删除的消息（分页）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq = :selfQq AND m.archived = false AND m.deleted = false ORDER BY m.serverRecvMs DESC, m.id DESC")
    List<Message> findMessagesByGroupIdAndSelfQqWithLimit(@Param("groupId") String groupId, @Param("selfQq") String selfQq, org.springframework.data.domain.Pageable pageable);

    /**
     * 统计群聊消息数量（未归档&未被用户删除，按登录者QQ过滤）
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.groupId = :groupId AND m.selfQq = :selfQq AND m.archived = false AND m.deleted = false")
    Long countActiveMessagesByGroupIdAndSelfQq(@Param("groupId") String groupId, @Param("selfQq") String selfQq);

    /**
     * 查询用户有消息的群聊列表（未被用户删除）
     */
    @Query("SELECT DISTINCT m.groupId FROM Message m WHERE m.selfQq = :selfQq AND m.archived = false AND m.deleted = false")
    List<String> findGroupIdsBySelfQq(@Param("selfQq") String selfQq);

    // ==================== 按多个用户QQ过滤的查询方法 ====================

    /**
     * 根据群聊ID和多个登录者QQ查询消息（按服务端接收时间倒序）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq IN :selfQqList ORDER BY m.serverRecvMs DESC, m.id DESC")
    List<Message> findByGroupIdAndSelfQqInOrderBySendTimeDesc(@Param("groupId") String groupId, @Param("selfQqList") List<String> selfQqList);

    /**
     * 根据群聊ID和多个登录者QQ查询未归档&未被用户删除的消息（分页）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq IN :selfQqList AND m.archived = false AND m.deleted = false ORDER BY m.serverRecvMs DESC, m.id DESC")
    List<Message> findMessagesByGroupIdAndSelfQqInWithLimit(@Param("groupId") String groupId, @Param("selfQqList") List<String> selfQqList, org.springframework.data.domain.Pageable pageable);

    /**
     * 统计群聊消息数量（按多个登录者QQ过滤，同时排除已用户删除）
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.groupId = :groupId AND m.selfQq IN :selfQqList AND m.archived = false AND m.deleted = false")
    Long countActiveMessagesByGroupIdAndSelfQqIn(@Param("groupId") String groupId, @Param("selfQqList") List<String> selfQqList);

    /**
     * 根据消息ID判断消息是否存在
     */
    boolean existsByMessageId(String messageId);

    /**
     * 根据 QQ 消息 ID 查询消息
     */
    java.util.Optional<Message> findByMessageId(String messageId);

    /**
     * 查询内容中包含指定路径的消息（用于文件删除后同步软删除消息）
     */
    @Query("SELECT m FROM Message m WHERE m.content LIKE %:path1% OR m.content LIKE %:path2%")
    List<Message> findByContentContainingPath(@Param("path1") String path1, @Param("path2") String path2);

    /**
     * 统计消息最多的QQ账号（取前10）
     */
    @Query("SELECT m.userQq, MAX(m.userNickname) as nickname, COUNT(m) as cnt FROM Message m WHERE m.archived = false GROUP BY m.userQq ORDER BY cnt DESC LIMIT 10")
    List<Object[]> findTopQQByMessageCount();

    /**
     * 获取指定 ID 之后的新消息（增量拉取，同时排除已被用户删除）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.selfQq IN :selfQqList AND m.archived = false AND m.deleted = false AND m.id > :afterId ORDER BY m.serverRecvMs ASC, m.id ASC")
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

    /**
     * 统计群聊未读消息数量（未归档&未被用户删除）
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.groupId = :groupId AND m.archived = false AND m.deleted = false")
    Long countUnreadMessages(@Param("groupId") String groupId);

    /**
     * 统计群聊未读消息数量（指定服务端接收毫秒时间之后，且未被用户删除）
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.groupId = :groupId AND m.serverRecvMs > :sinceMs AND m.archived = false AND m.deleted = false")
    Long countUnreadMessagesSince(@Param("groupId") String groupId, @Param("sinceMs") Long sinceMs);

    /**
     * 查询群聊最新服务端接收毫秒时间（排除已归档&已被用户删除）
     */
    @Query("SELECT MAX(m.serverRecvMs) FROM Message m WHERE m.groupId = :groupId AND m.archived = false AND m.deleted = false")
    Long findLastMessageTime(@Param("groupId") String groupId);

    /**
     * 查询群聊中所有去重发送者的 QQ 号与昵称（用于 @消息昵称解析）
     */
    @Query("SELECT DISTINCT m.userQq, m.userNickname FROM Message m WHERE m.groupId = :groupId AND m.userQq IS NOT NULL AND m.userNickname IS NOT NULL AND m.userNickname <> '' AND m.deleted = false")
    List<Object[]> findDistinctSendersByGroupId(@Param("groupId") String groupId);

    /**
     * 查询群聊中包含 @CQ 码的消息内容（用于发现被 @ 但未发过消息的成员）
     */
    @Query("SELECT DISTINCT m.content FROM Message m WHERE m.groupId = :groupId AND m.content LIKE '%[CQ:at%' AND m.deleted = false")
    List<String> findAtContentsByGroupId(@Param("groupId") String groupId);
}
