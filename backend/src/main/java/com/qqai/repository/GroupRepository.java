package com.qqai.repository;

import com.qqai.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {
    
    /**
     * 根据群号查找群聊（所有登录账号）
     */
    Optional<Group> findByGroupId(String groupId);
    
    /**
     * 根据群号和登录者QQ查找群聊
     */
    Optional<Group> findByGroupIdAndOwnerQq(String groupId, String ownerQq);
    
    /**
     * 根据登录者QQ查找所有群聊
     */
    List<Group> findByOwnerQq(String ownerQq);
    
    /**
     * 根据登录者QQ查找活跃群聊
     */
    List<Group> findByOwnerQqAndActiveTrue(String ownerQq);
    
    /**
     * 查询所有活跃群聊
     */
    List<Group> findByActiveTrue();
    
    /**
     * 检查群号是否存在（所有登录账号）
     */
    boolean existsByGroupId(String groupId);
    
    /**
     * 检查指定登录者的群号是否存在
     */
    boolean existsByGroupIdAndOwnerQq(String groupId, String ownerQq);
}