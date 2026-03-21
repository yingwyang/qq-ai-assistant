package com.qqai.repository;

import com.qqai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * 根据QQ号码查找用户
     */
    Optional<User> findByQq(String qq);
    
    /**
     * 根据token查找用户
     */
    Optional<User> findByToken(String token);
    
    /**
     * 检查QQ号码是否存在
     */
    boolean existsByQq(String qq);
}