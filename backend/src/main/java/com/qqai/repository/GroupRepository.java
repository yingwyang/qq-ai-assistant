package com.qqai.repository;

import com.qqai.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {
    
    /**
     * 根据群号查找群聊（所有登录账号，可能有多条记录对应不同 ownerQq）
     */
    List<Group> findByGroupId(String groupId);

    /**
     * 批量按群号取群聊记录（同一群号可能多行，调用方按 groupId 取第一条有效群名）。
     * 用于替代逐群调用 findByGroupId 的 N+1 查询。
     */
    @Query("SELECT g FROM Group g WHERE g.groupId IN :groupIds")
    List<Group> findByGroupIdIn(@Param("groupIds") List<String> groupIds);
    
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
     * 批量按绑定 QQ 取活跃群聊，替代「逐个 QQ 调用 findByOwnerQqAndActiveTrue」的 N+1。
     */
    @Query("SELECT g FROM Group g WHERE g.ownerQq IN :ownerQqList AND g.active = true")
    List<Group> findByOwnerQqInAndActiveTrue(@Param("ownerQqList") List<String> ownerQqList);
    
    /**
     * 查询所有活跃群聊
     */
    List<Group> findByActiveTrue();

    /**
     * 根据群类型查找活跃群聊
     */
    List<Group> findByGroupTypeAndActiveTrue(String groupType);
    
    /**
     * 检查群号是否存在（所有登录账号）
     */
    boolean existsByGroupId(String groupId);
    
    /**
     * 检查指定登录者的群号是否存在
     */
    boolean existsByGroupIdAndOwnerQq(String groupId, String ownerQq);

    /**
     * 单条 SQL 判断该群是否属于当前用户绑定的任一 QQ，且 active=true
     */
    long countByGroupIdAndOwnerQqInAndActiveTrue(String groupId, List<String> ownerQqList);

    /**
     * 统计当前用户绑定的任一 QQ 名下的群聊总数
     */
    long countByOwnerQqIn(List<String> ownerQqList);

    /**
     * 统计当前用户绑定的任一 QQ 名下的活跃群聊数量
     */
    long countByOwnerQqInAndActiveTrue(List<String> ownerQqList);

    /**
     * 防御性包装
     */
    default long safeCountByOwnerQqIn(List<String> ownerQqList) {
        if (ownerQqList == null || ownerQqList.isEmpty()) return 0L;
        return countByOwnerQqIn(ownerQqList);
    }

    /**
     * 防御性包装
     */
    default long safeCountByOwnerQqInAndActiveTrue(List<String> ownerQqList) {
        if (ownerQqList == null || ownerQqList.isEmpty()) return 0L;
        return countByOwnerQqInAndActiveTrue(ownerQqList);
    }

    /**
     * 防御性包装
     */
    default long safeCountByGroupIdAndOwnerQqInAndActiveTrue(String groupId, List<String> ownerQqList) {
        if (ownerQqList == null || ownerQqList.isEmpty()) return 0L;
        return countByGroupIdAndOwnerQqInAndActiveTrue(groupId, ownerQqList);
    }

    /**
     * 防御性包装 findByGroupId
     */
    default List<Group> safeFindByGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) return java.util.Collections.emptyList();
        List<Group> result = findByGroupId(groupId);
        return result != null ? result : java.util.Collections.emptyList();
    }

    /**
     * 根据多个 ownerQq 查找群聊
     */
    List<Group> findByOwnerQqIn(List<String> ownerQqList);

    /**
     * 根据多个 ownerQq 删除群聊
     */
    void deleteByOwnerQqIn(List<String> ownerQqList);
}