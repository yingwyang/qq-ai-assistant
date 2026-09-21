package com.qqai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主配置文件结构守卫。
 *
 * 背景（真实踩坑）：`src/main/resources/application.yml` 后半段有一个 `---` 分隔的 **dev profile 文档**，
 * 写在分隔符之后的配置只在 `--spring.profiles.active=dev` 时生效。本次接入 Actuator 时把
 * `management:` 段误写在分隔符之后，结果默认 profile 下 `/actuator` 只暴露了 health（且没有依赖详情），
 * 排查成本很高。这里用源码扫描把这条约定固化下来。
 */
class ApplicationYamlStructureTest {

    private static final Path MAIN_YML = Paths.get("src", "main", "resources", "application.yml");

    private String readMainYml() throws IOException {
        assertTrue(Files.exists(MAIN_YML),
                "找不到主配置：" + MAIN_YML.toAbsolutePath() + "（测试工作目录应为 backend/）");
        return Files.readString(MAIN_YML);
    }

    /** 取第一个 `---` 文档分隔符之前的内容（默认 profile 生效的部分） */
    private String defaultDocument(String yml) {
        String[] lines = yml.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.trim().equals("---")) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("management 配置必须在默认文档里（写在 dev 文档里默认 profile 不生效）")
    void managementConfigLivesInDefaultDocument() throws IOException {
        String defaultDoc = defaultDocument(readMainYml());
        List<String> lines = defaultDoc.lines().toList();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("management:")),
                "management: 必须出现在第一个 YAML 文档中（`---` 之前），否则默认 profile 下不会生效");
        assertTrue(lines.stream().anyMatch(l -> l.contains("include: health,info,metrics")),
                "默认文档里应显式暴露 health/info/metrics 三个端点");
        assertTrue(lines.stream().anyMatch(l -> l.contains("show-details: always")),
                "默认文档里应打开健康详情（端点已收权为 ADMIN，详情用于排障）");
    }

    @Test
    @DisplayName("管理员初始密码等安全配置也必须在默认文档里")
    void securityRelevantConfigLivesInDefaultDocument() throws IOException {
        String defaultDoc = defaultDocument(readMainYml());
        assertTrue(defaultDoc.contains("cookie:"), "app.cookie 段（Cookie secure）应在默认文档中");
        assertTrue(defaultDoc.contains("jwt:"), "jwt 段应在默认文档中");
    }
}
