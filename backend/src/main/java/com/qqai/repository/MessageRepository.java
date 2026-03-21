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
}
