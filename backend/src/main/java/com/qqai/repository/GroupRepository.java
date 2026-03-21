package com.qqai.repository;

import com.qqai.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {
    
    /**
     * 根据群号查找群聊
     */
    Optional<Group> findByGroupId(String groupId);
    
    /**
     * 检查群号是否存在
     */
    boolean existsByGroupId(String groupId);
}