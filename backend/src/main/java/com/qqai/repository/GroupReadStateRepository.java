package com.qqai.repository;

import com.qqai.entity.GroupReadState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupReadStateRepository extends JpaRepository<GroupReadState, Long> {

    Optional<GroupReadState> findByUserIdAndGroupId(Long userId, String groupId);

    List<GroupReadState> findByUserId(Long userId);

    /**
     * 批量查询某个用户对若干群聊的 lastReadTime
     */
    @Query("SELECT g FROM GroupReadState g WHERE g.userId = :userId AND g.groupId IN :groupIds")
    List<GroupReadState> findByUserIdAndGroupIdIn(
            @Param("userId") Long userId,
            @Param("groupIds") List<String> groupIds);

    /**
     * MySQL 原生 upsert：存在则更新，不存在则插入。
     * 依赖 (user_id, group_id) 组合唯一索引。
     */
    @Modifying
    @Query(
            value = "INSERT INTO group_read_state (user_id, group_id, last_read_time, created_at, updated_at) " +
                    "VALUES (:userId, :groupId, :now, :now, :now) " +
                    "ON DUPLICATE KEY UPDATE last_read_time = :now, updated_at = :now",
            nativeQuery = true
    )
    int upsertReadState(@Param("userId") Long userId,
                        @Param("groupId") String groupId,
                        @Param("now") LocalDateTime now);

    /**
     * 把某个用户所有群聊的 last_read_time 一次性更新为 now
     */
    @Modifying
    @Query("UPDATE GroupReadState g SET g.lastReadTime = :now, g.updatedAt = :now WHERE g.userId = :userId")
    int markAllAsRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 根据用户ID和群号列表删除阅读状态
     */
    @Modifying
    @Query("DELETE FROM GroupReadState g WHERE g.userId = :userId AND g.groupId IN :groupIds")
    int deleteByUserIdAndGroupIdIn(@Param("userId") Long userId, @Param("groupIds") List<String> groupIds);
}
