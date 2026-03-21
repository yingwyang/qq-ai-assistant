package com.qqai.repository;

import com.qqai.entity.UserGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserGroupRepository extends JpaRepository<UserGroup, Long> {
    
    /**
     * 根据用户QQ查找所属群聊
     */
    List<UserGroup> findByUserId(String userId);
    
    /**
     * 根据群号查找群成员
     */
    List<UserGroup> findByGroupId(String groupId);
    
    /**
     * 查找用户和群聊的关联关系
     */
    UserGroup findByUserIdAndGroupId(String userId, String groupId);
    
    /**
     * 检查用户是否在群聊中
     */
    boolean existsByUserIdAndGroupId(String userId, String groupId);
    
    /**
     * 查询用户的活跃群聊
     */
    @Query("SELECT ug FROM UserGroup ug WHERE ug.userId = :userId AND ug.active = true")
    List<UserGroup> findActiveGroupsByUserId(@Param("userId") String userId);
}