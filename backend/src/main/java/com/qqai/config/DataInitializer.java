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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

        boolean passwordFromEnv = adminInitPassword != null && adminInitPassword.length() >= 8;
        String password = resolveInitPassword();
        User admin = new User();
        admin.setUsername("admin");
        admin.setNickname("系统管理员");
        admin.setPassword(passwordEncoder.encode(password));
        admin.setRole("ADMIN");
        admin.setActive(true);
        admin.setTokenVersion(0);
        userService.save(admin);

        // 安全：初始密码不再写进日志（日志会被长期保留、被运维/排障人员读取）。
        // 随机密码改为落一次性文件（backend/data/ 已在 .gitignore 中）；环境变量提供的密码则无需落盘。
        Path passwordFile = null;
        if (!passwordFromEnv) {
            try {
                Path dir = Paths.get("data");
                Files.createDirectories(dir);
                passwordFile = dir.resolve("initial-admin-password.txt");
                Files.writeString(passwordFile,
                        "初始管理员账号: admin\n初始密码: " + password
                                + "\n请登录后立即修改密码，并删除本文件。\n");
            } catch (Exception e) {
                log.error("写入初始密码文件失败，请改用 ADMIN_INIT_PASSWORD 环境变量提供初始密码", e);
            }
        }

        log.warn("================================================================");
        log.warn("【安全】已创建初始管理员账号 admin（初始密码未写入日志）。");
        if (passwordFromEnv) {
            log.warn("【安全】初始密码取自 ADMIN_INIT_PASSWORD 环境变量。");
        } else if (passwordFile != null) {
            log.warn("【安全】初始密码已写入文件：{}", passwordFile.toAbsolutePath());
            log.warn("【安全】请登录后立即修改密码，并删除该文件。");
        } else {
            log.warn("【安全】无法写入密码文件，请设置 ADMIN_INIT_PASSWORD 环境变量后重启以重置密码。");
        }
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
