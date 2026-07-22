package com.qqai.repository;

import com.qqai.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
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
}