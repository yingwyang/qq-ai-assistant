package com.qqai.repository;

import com.qqai.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    
    /**
     * 根据用户名/账号查找用户
     */
    Optional<User> findByUsername(String username);
    
    /**
     * 根据token查找用户
     */
    Optional<User> findByToken(String token);
    
    /**
     * 检查用户名/账号是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 按用户名或昵称模糊搜索（分页）
     */
    Page<User> findByUsernameContainingOrNicknameContaining(String username, String nickname, Pageable pageable);

    /**
     * 统计「可用管理员」数量：role=ADMIN 且未被禁用。
     * 用于保证系统里始终至少有一位能登录后台的管理员。
     */
    long countByRoleIgnoreCaseAndActiveTrue(String role);
}
