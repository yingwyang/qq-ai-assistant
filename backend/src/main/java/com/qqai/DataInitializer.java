package com.qqai;

import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 应用启动初始化：检查并创建默认管理员账号
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        // 检查是否已存在管理员
        boolean hasAdmin = userRepository.findAll().stream()
                .anyMatch(user -> "ADMIN".equals(user.getRole()));

        if (!hasAdmin) {
            User admin = new User();
            admin.setQq("admin");
            admin.setNickname("系统管理员");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setRole("ADMIN");
            admin.setActive(true);
            userRepository.save(admin);

            System.out.println("========================================");
            System.out.println("  默认管理员账号已创建");
            System.out.println("  账号: admin");
            System.out.println("  密码: admin123");
            System.out.println("========================================");
        }
    }
}
