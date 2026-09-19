package com.qqai;

import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import com.qqai.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「最后一位可用管理员」守卫测试。
 *
 * 背景：管理员可以给自己降权 / 禁用自己 / 删除自己，一旦系统里再没有可登录的管理员，
 * /admin 就彻底进不去了（只能改数据库）。这里覆盖三条会触发该风险的路径判定。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminLastAdminGuardTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    private User save(String username, String role, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("x");
        user.setRole(role);
        user.setActive(active);
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Test
    @DisplayName("只有一位可用管理员时，降权 / 禁用 / 删除都要被拦住")
    void lastActiveAdminIsProtected() {
        User onlyAdmin = save("solo", "ADMIN", true);
        assertEquals(1, userService.countActiveAdmins());

        assertTrue(userService.wouldRemoveLastAdmin(onlyAdmin, true), "降权应被拦截");
        assertTrue(userService.wouldRemoveLastAdmin(onlyAdmin, true), "禁用应被拦截");
        assertTrue(userService.wouldRemoveLastAdmin(onlyAdmin, true), "删除应被拦截");
    }

    @Test
    @DisplayName("还有第二位可用管理员时放行")
    void allowedWhenAnotherActiveAdminExists() {
        User first = save("admin1", "ADMIN", true);
        save("admin2", "ADMIN", true);
        assertEquals(2, userService.countActiveAdmins());
        assertFalse(userService.wouldRemoveLastAdmin(first, true));
    }

    @Test
    @DisplayName("被禁用或非管理员账号不受保护")
    void inactiveOrNonAdminIsNotProtected() {
        save("realAdmin", "ADMIN", true);
        User disabledAdmin = save("disabledAdmin", "ADMIN", false);
        User normalUser = save("normal", "USER", true);

        // 禁用的管理员本来就不能登录后台，删掉/降权都不影响可用管理员数量
        assertFalse(userService.wouldRemoveLastAdmin(disabledAdmin, true));
        assertFalse(userService.wouldRemoveLastAdmin(normalUser, true));
        assertEquals(1, userService.countActiveAdmins());
    }

    @Test
    @DisplayName("提权 / 启用这类不利于失去管理员的操作永远放行")
    void promotingOrEnablingIsAlwaysAllowed() {
        User onlyAdmin = save("solo", "ADMIN", true);
        User normalUser = save("normal", "USER", true);
        // willLoseAdmin=false 表示这次操作不会让目标失去可用管理员身份
        assertFalse(userService.wouldRemoveLastAdmin(onlyAdmin, false));
        assertFalse(userService.wouldRemoveLastAdmin(normalUser, false));
    }

    @Test
    @DisplayName("两位管理员里禁用一位后，另一位就进入受保护状态")
    void protectionFollowsActiveCount() {
        User admin1 = save("admin1", "ADMIN", true);
        User admin2 = save("admin2", "ADMIN", true);

        assertFalse(userService.wouldRemoveLastAdmin(admin1, true));

        // 模拟 admin2 被禁用
        admin2.setActive(false);
        userRepository.save(admin2);

        assertEquals(1, userService.countActiveAdmins());
        assertTrue(userService.wouldRemoveLastAdmin(admin1, true), "现在 admin1 是最后一位，必须受保护");
        assertFalse(userService.wouldRemoveLastAdmin(admin2, true), "已禁用的账号本身不再受保护");
    }

    @Test
    @DisplayName("角色大小写不影响判定")
    void roleComparisonIsCaseInsensitive() {
        User admin = save("admin", "admin", true);
        assertEquals(1, userService.countActiveAdmins());
        assertTrue(userService.isActiveAdmin(admin));
        assertTrue(userService.wouldRemoveLastAdmin(admin, true));
    }
}
