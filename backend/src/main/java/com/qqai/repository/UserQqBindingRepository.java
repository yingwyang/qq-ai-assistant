package com.qqai.repository;

import com.qqai.entity.UserQqBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserQqBindingRepository extends JpaRepository<UserQqBinding, Long> {
    
    /**
     * 根据用户ID查找所有绑定的QQ账号
     */
    List<UserQqBinding> findByUserId(Long userId);
    
    /**
     * 根据用户ID查找激活的QQ账号
     */
    List<UserQqBinding> findByUserIdAndActiveTrue(Long userId);
    
    /**
     * 根据用户ID查找默认QQ账号
     */
    Optional<UserQqBinding> findByUserIdAndIsDefaultTrue(Long userId);
    
    /**
     * 查找指定QQ号的绑定记录
     */
    Optional<UserQqBinding> findByQqNumber(String qqNumber);
    
    /**
     * 检查用户是否已绑定指定QQ号
     */
    boolean existsByUserIdAndQqNumber(Long userId, String qqNumber);

    /**
     * 检查用户是否已激活绑定指定QQ号
     */
    boolean existsByUserIdAndQqNumberAndActiveTrue(Long userId, String qqNumber);

    /**
     * 查找用户指定QQ号的绑定记录
     */
    Optional<UserQqBinding> findByUserIdAndQqNumber(Long userId, String qqNumber);

    /**
     * 统计用户绑定的QQ账号数量
     */
    long countByUserId(Long userId);

    /**
     * 统计用户激活的QQ账号数量
     */
    long countByUserIdAndActiveTrue(Long userId);
    
    /**
     * 根据QQ号查找所有绑定记录
     */
    List<UserQqBinding> findByQqNumberAndActiveTrue(String qqNumber);
}
