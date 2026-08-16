package com.qqai.config;

import com.qqai.entity.User;
import com.qqai.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;

/**
 * 启动时保证存在至少一个管理员账号。
 *
 * 安全策略:不再使用固定弱密码 admin123。
 * - 若配置了环境变量 ADMIN_INIT_PASSWORD,则用该值创建初始管理员;
 * - 否则生成随机密码,仅在日志中输出一次(请尽快登录修改);
 * - 已存在管理员时不做任何操作。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789@#$%&";

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${app.admin.init-password:}")
    private String adminInitPassword;

    @Override
    public void run(String... args) {
        if (userService.findByUsername("admin").isPresent()) {
            log.info("管理员账号已存在，跳过初始化");
            return;
        }

        String password = resolveInitPassword();
        User admin = new User();
        admin.setUsername("admin");
        admin.setNickname("系统管理员");
        admin.setPassword(passwordEncoder.encode(password));
        admin.setRole("ADMIN");
        admin.setActive(true);
        admin.setTokenVersion(0);
        userService.save(admin);

        log.warn("================================================================");
        log.warn("【安全】已创建初始管理员账号 admin,初始密码: {}", password);
        log.warn("【安全】请立即登录并在个人中心修改该密码。");
        log.warn("================================================================");
    }

    private String resolveInitPassword() {
        if (adminInitPassword != null && !adminInitPassword.isBlank()) {
            if (adminInitPassword.length() < 8) {
                log.warn("ADMIN_INIT_PASSWORD 长度不足 8 位,改生成随机密码");
            } else {
                return adminInitPassword;
            }
        }
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
