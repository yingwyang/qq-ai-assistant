package com.qqai.repository;

import com.qqai.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long>, JpaSpecificationExecutor<Message> {
    
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

    // ===== AI 摘要阶段 2：批量补摘要的候选集与统计 =====
    /** 未处理且未删除的消息（批量投递候选集，具体过滤条件在服务层按参数处理） */
    List<Message> findByProcessedFalseAndDeletedFalse();

    /** 剩余待处理条数 */
    long countByProcessedFalseAndDeletedFalse();

    /** 指定时间之后完成摘要的条数（用于统计"今日已完成"） */
    long countByAiSummarizedAtGreaterThanEqual(LocalDateTime time);

    // ===== AI 摘要阶段 3：群日报的当天消息候选集 =====
    /**
     * 查询某群在 [start, end) 内参与群日报的消息：
     * 未被删除、指定消息类型、内容非空且长度不小于 minLength，按发送时间升序。
     *
     * <p>消息类型以参数传入（而不是在 JPQL 里写枚举字面量），
     * 避免不同 Hibernate 版本对枚举字面量写法的差异。</p>
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.deleted = false "
            + "AND m.messageType = :messageType AND m.content IS NOT NULL "
            + "AND LENGTH(m.content) >= :minLength "
            + "AND m.sendTime >= :start AND m.sendTime < :end "
            + "ORDER BY m.sendTime ASC, m.id ASC")
    List<Message> findDigestCandidates(@Param("groupId") String groupId,
                                       @Param("messageType") Message.MessageType messageType,
                                       @Param("minLength") int minLength,
                                       @Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    /**
     * 查询在 [start, end) 内有消息的群号（定时群日报用来确定「今天有消息的群」）。
     *
     * <p>这里只做粗筛（未被删除 + 有非空内容），更严格的「消息类型 = TEXT、长度 ≥ minLength」
     * 由 {@code GroupDigestService} 按运行时配置处理：粗筛结果偏多只会多投递几条任务，
     * 消费者会以 400「当天没有可摘要的消息」快速确认掉，不会重复消耗大模型额度。</p>
     */
    @Query("SELECT DISTINCT m.groupId FROM Message m WHERE m.deleted = false "
            + "AND m.content IS NOT NULL AND LENGTH(m.content) > 0 "
            + "AND m.sendTime >= :start AND m.sendTime < :end")
    List<String> findGroupIdsWithMessagesBetween(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);
    
    /**
     * 根据消息类型查询
     */
    List<Message> findByMessageType(Message.MessageType messageType);

    /**
     * 根据消息类型分页查询
     */
    Page<Message> findByMessageType(Message.MessageType messageType, Pageable pageable);

    /**
     * 根据群号和消息类型列表查询未删除消息（用于右键按类型删除）
     */
    @Query("SELECT m FROM Message m WHERE m.groupId = :groupId AND m.messageType IN :types AND m.deleted = false AND m.archived = false")
    List<Message> findByGroupIdAndMessageTypes(
            @Param("groupId") String groupId,
            @Param("types") List<Message.MessageType> types);
    
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
    @Query(value = "SELECT group_id, MAX(group_name) AS groupName FROM messages WHERE archived = false AND deleted = false " +
            "GROUP BY group_id ORDER BY MAX(server_recv_ms) DESC LIMIT 10", nativeQuery = true)
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
     * @deprecated QQ message_id 仅在群内唯一,跨群会重复;请使用 {@link #existsByMessageIdAndGroupId(String, String)}
     */
    @Deprecated
    boolean existsByMessageId(String messageId);

    /**
     * 按 (群, QQ消息ID) 判断消息是否已存在。
     * NapCat 的 message_id 只在同一账号+群内唯一,跨群可能重复,
     * 因此去重必须以 groupId + messageId 组合为准。
     */
    boolean existsByMessageIdAndGroupId(String messageId, String groupId);

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
    @Query(value = "SELECT user_qq, MAX(user_nickname) AS nickname, COUNT(*) AS cnt FROM messages WHERE archived = false " +
            "GROUP BY user_qq ORDER BY cnt DESC LIMIT 10", nativeQuery = true)
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
     * 统计指定时间范围内活跃用户数（按 userQq 去重）
     */
    @Query("SELECT COUNT(DISTINCT m.userQq) FROM Message m WHERE m.sendTime >= :start AND m.sendTime < :end AND m.userQq IS NOT NULL")
    Long countActiveUsersBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 按小时统计指定日期的消息数量（用于热力图）
     */
    @Query(value = "SELECT HOUR(send_time) AS hour, COUNT(*) AS cnt FROM messages WHERE DATE(send_time) = :date " +
            "GROUP BY HOUR(send_time) ORDER BY hour", nativeQuery = true)
    List<Object[]> countByHour(@Param("date") java.time.LocalDate date);

    /**
     * 查询群聊最新服务端接收毫秒时间（排除已归档&已被用户删除）
     */
    @Query("SELECT MAX(m.serverRecvMs) FROM Message m WHERE m.groupId = :groupId AND m.archived = false AND m.deleted = false")
    Long findLastMessageTime(@Param("groupId") String groupId);

    /**
     * 批量查询多个群聊的最新服务端接收毫秒时间（排除已归档&已被用户删除）
     */
    @Query("SELECT m.groupId, MAX(m.serverRecvMs) FROM Message m WHERE m.groupId IN :groupIds AND m.archived = false AND m.deleted = false GROUP BY m.groupId")
    List<Object[]> findLastMessageTimes(@Param("groupIds") List<String> groupIds);

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

    /**
     * 批量统计多个群聊的最后消息时间和未读数（替代 per-group 的 N+1 循环）。
     * unread_count 规则：如果该群用户没有 last_read_time（即 s.group_id IS NULL），则返回 0，
     * 只有存在 last_read_time 时，才统计 server_recv_ms > last_read_epoch 的消息条数。
     */
    @Query(value = """
        SELECT
          g.group_id,
          COALESCE(MAX(m.server_recv_ms), 0) AS last_recv_ms,
          CASE
            WHEN MAX(s.last_read_epoch) IS NULL THEN 0
            ELSE SUM(CASE WHEN m.server_recv_ms > COALESCE(s.last_read_epoch, 0) THEN 1 ELSE 0 END)
          END AS unread_count
        FROM chat_groups g
        LEFT JOIN messages m
          ON m.group_id = g.group_id AND m.archived=false AND m.deleted=false
        LEFT JOIN (
          SELECT group_id, CAST(UNIX_TIMESTAMP(last_read_time)*1000 AS SIGNED) AS last_read_epoch
          FROM group_read_state WHERE user_id = :userId
        ) s ON s.group_id = g.group_id
        WHERE g.group_id IN :groupIds AND g.active=true
        GROUP BY g.group_id
        """, nativeQuery = true)
    List<Object[]> recentGroupStats(@Param("userId") Long userId, @Param("groupIds") List<String> groupIds);

    // ==================== 用户级 Dashboard 按多个 selfQq 过滤的统计方法 ====================

    /**
     * 统计当前用户 QQ 账号下的消息总数
     */
    long countBySelfQqIn(List<String> selfQqs);

    /**
     * 防御性包装：空列表时直接返回 0，避免生成空 IN 子句
     */
    default long safeCountBySelfQqIn(List<String> selfQqs) {
        if (selfQqs == null || selfQqs.isEmpty()) return 0L;
        return countBySelfQqIn(selfQqs);
    }

    /**
     * 统计指定时间范围内、当前用户 QQ 账号下的消息数量
     */
    long countBySendTimeBetweenAndSelfQqIn(LocalDateTime start, LocalDateTime end, List<String> selfQqs);

    /**
     * 防御性包装：空列表时直接返回 0，避免生成空 IN 子句
     */
    default long safeCountBySendTimeBetweenAndSelfQqIn(LocalDateTime start, LocalDateTime end, List<String> selfQqs) {
        if (selfQqs == null || selfQqs.isEmpty()) return 0L;
        return countBySendTimeBetweenAndSelfQqIn(start, end, selfQqs);
    }

    /**
     * 按消息类型统计数量（按多个登录者QQ过滤）
     */
    @Query("SELECT m.messageType, COUNT(m) FROM Message m WHERE m.selfQq IN :selfQqs GROUP BY m.messageType")
    List<Object[]> countByMessageTypeAndSelfQqIn(@Param("selfQqs") List<String> selfQqs);

    /**
     * 防御性包装：空列表时直接返回空集合
     */
    default List<Object[]> safeCountByMessageTypeAndSelfQqIn(List<String> selfQqs) {
        if (selfQqs == null || selfQqs.isEmpty()) return java.util.Collections.emptyList();
        List<Object[]> result = countByMessageTypeAndSelfQqIn(selfQqs);
        return result != null ? result : java.util.Collections.emptyList();
    }

    /**
     * 按小时统计指定日期的消息数量（按多个登录者QQ过滤，热力图）
     */
    @Query(value = "SELECT HOUR(m.send_time) AS hour, COUNT(*) AS cnt FROM messages m " +
            "WHERE DATE(m.send_time) = DATE(:today) AND m.self_qq IN (:selfQqs) " +
            "GROUP BY HOUR(m.send_time) ORDER BY hour", nativeQuery = true)
    List<Object[]> countByHourAndSelfQqIn(@Param("today") LocalDateTime today, @Param("selfQqs") List<String> selfQqs);

    /**
     * 防御性包装
     */
    default List<Object[]> safeCountByHourAndSelfQqIn(LocalDateTime today, List<String> selfQqs) {
        if (selfQqs == null || selfQqs.isEmpty()) return java.util.Collections.emptyList();
        List<Object[]> result = countByHourAndSelfQqIn(today, selfQqs);
        return result != null ? result : java.util.Collections.emptyList();
    }

    /**
     * 统计消息最多的 QQ 账号（按多个登录者QQ过滤，分页取前N）
     */
    @Query("SELECT m.userQq, MAX(m.userNickname) AS nickname, COUNT(m) AS cnt FROM Message m " +
            "WHERE m.archived = false AND m.selfQq IN :selfQqs GROUP BY m.userQq ORDER BY cnt DESC")
    List<Object[]> findTopQQByMessageCountAndSelfQqIn(@Param("selfQqs") List<String> selfQqs, Pageable pageable);

    /**
     * 防御性包装
     */
    default List<Object[]> safeFindTopQQByMessageCountAndSelfQqIn(List<String> selfQqs, Pageable pageable) {
        if (selfQqs == null || selfQqs.isEmpty()) return java.util.Collections.emptyList();
        List<Object[]> result = findTopQQByMessageCountAndSelfQqIn(selfQqs, pageable);
        return result != null ? result : java.util.Collections.emptyList();
    }

    /**
     * 查询当前用户 QQ 账号下有消息的去重群聊列表（未归档&未被用户删除）
     */
    @Query("SELECT DISTINCT m.groupId FROM Message m WHERE m.selfQq IN :selfQqs AND m.archived = false AND m.deleted = false")
    List<String> findGroupIdsBySelfQqIn(@Param("selfQqs") List<String> selfQqs);

    /**
     * 防御性包装
     */
    default List<String> safeFindGroupIdsBySelfQqIn(List<String> selfQqs) {
        if (selfQqs == null || selfQqs.isEmpty()) return java.util.Collections.emptyList();
        List<String> result = findGroupIdsBySelfQqIn(selfQqs);
        return result != null ? result : java.util.Collections.emptyList();
    }

    /**
     * 防御性包装 countActiveMessagesByGroupIdAndSelfQqIn
     */
    default Long safeCountActiveMessagesByGroupIdAndSelfQqIn(String groupId, List<String> selfQqList) {
        if (selfQqList == null || selfQqList.isEmpty()) return 0L;
        Long result = countActiveMessagesByGroupIdAndSelfQqIn(groupId, selfQqList);
        return result != null ? result : 0L;
    }

    /**
     * 根据多个群号删除消息
     */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.groupId IN :groupIds")
    void deleteByGroupIdIn(@Param("groupIds") List<String> groupIds);

    /**
     * 根据多个群号查找消息（用于级联删除前提取文件引用）
     */
    List<Message> findByGroupIdIn(List<String> groupIds);
}
