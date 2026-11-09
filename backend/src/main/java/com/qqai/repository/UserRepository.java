package com.qqai.repository;

import com.qqai.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * 鉴权只需判断「是否启用」与 tokenVersion，用最小投影查两个字段即可。
     * 相比 {@code findById}：不加载整个实体、不进持久化上下文，鉴权路径（每请求一次）更省。
     */
    interface AuthSnapshot {
        Boolean getActive();

        Integer getTokenVersion();
    }

    @Query("SELECT u.active AS active, u.tokenVersion AS tokenVersion FROM User u WHERE u.id = :id")
    Optional<AuthSnapshot> findAuthSnapshotById(@Param("id") Long id);

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
