package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    @Autowired
    private Environment env;

    private static final String MASK = "***";

    /** 覆盖文件所在目录与文件名（与 writeOverrides 共用，保证读写同一份文件） */
    private static final String OVERRIDE_DIR = "data";
    private static final String OVERRIDE_FILE = "application-override.properties";

    private static final List<ConfigGroupDef> GROUP_DEFS = List.of(
            new ConfigGroupDef("AstrBot", List.of(
                    new ConfigItemDef("astrbot.api-url", "API 地址", false, false,
                            "AstrBot 的 HTTP 接口地址，形如 http://127.0.0.1:6185"),
                    new ConfigItemDef("astrbot.token", "Token", true, true,
                            "AstrBot 控制台 → 配置 → API Key")
            )),
            new ConfigGroupDef("NapCat", List.of(
                    new ConfigItemDef("napcat.api-url", "API 地址", false, false,
                            "NapCat 的 HTTP 接口地址，形如 http://127.0.0.1:3000"),
                    new ConfigItemDef("napcat.token", "Token", true, true,
                            "与 NapCat WebUI 中配置的 token 保持一致"),
                    new ConfigItemDef("napcat.webhook-token", "Webhook Token", true, true,
                            "NapCat 上报事件时携带的校验 token")
            )),
            new ConfigGroupDef("GPT-SoVITS", List.of(
                    new ConfigItemDef("gpt-sovits.api-url", "API 地址", false, false,
                            "GPT-SoVITS 推理服务地址，形如 http://127.0.0.1:9880"),
                    new ConfigItemDef("gpt-sovits.token", "Token", true, true,
                            "留空表示不校验")
            )),
            new ConfigGroupDef("MinIO", List.of(
                    new ConfigItemDef("minio.endpoint", "Endpoint", false, false,
                            "对象存储地址，形如 http://127.0.0.1:9000；未部署 MinIO 时媒体文件会落到本地磁盘"),
                    new ConfigItemDef("minio.access-key", "Access Key", true, true, "MinIO 访问密钥 ID"),
                    new ConfigItemDef("minio.secret-key", "Secret Key", true, true, "MinIO 访问密钥")
            )),
            new ConfigGroupDef("JWT", List.of(
                    new ConfigItemDef("jwt.secret", "Secret", true, true,
                            "签发登录 token 的密钥，修改后所有已登录用户都需要重新登录"),
                    new ConfigItemDef("jwt.expiration", "过期时间(ms)", false, true,
                            "正整数，如 86400000 表示 24 小时")
            ))
    );

    public List<ConfigGroup> getConfig() {
        List<ConfigGroup> groups = new ArrayList<>();
        for (ConfigGroupDef def : GROUP_DEFS) {
            List<ConfigItem> items = new ArrayList<>();
            for (ConfigItemDef itemDef : def.items) {
                String value = env.getProperty(itemDef.key, "");
                String displayValue = itemDef.secret ? MASK : value;
                items.add(new ConfigItem(itemDef.key, itemDef.label, displayValue, itemDef.secret,
                        itemDef.restartRequired, itemDef.description));
            }
            groups.add(new ConfigGroup(def.name, items));
        }
        return groups;
    }

    public List<String> updateConfig(Map<String, String> updates) {
        List<String> restartRequiredKeys = new ArrayList<>();

        // 1. Apply runtime overrides via System.setProperty
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }

        // 2. Persist to application.yml override file
        try {
            writeOverrides(updates);
        } catch (IOException e) {
            log.warn("写入配置覆盖文件失败，运行时覆盖已生效但重启后可能丢失: {}", e.getMessage());
        }

        // 3. Check which keys require restart
        for (ConfigGroupDef def : GROUP_DEFS) {
            for (ConfigItemDef itemDef : def.items) {
                if (itemDef.restartRequired && updates.containsKey(itemDef.key)) {
                    restartRequiredKeys.add(itemDef.label + " (" + itemDef.key + ")");
                }
            }
        }

        return restartRequiredKeys;
    }

    private void writeOverrides(Map<String, String> updates) throws IOException {
        Path configDir = Path.of(OVERRIDE_DIR);
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        Path overrideFile = configDir.resolve(OVERRIDE_FILE);

        // Read existing overrides
        Map<String, String> existing = readOverrides(overrideFile);

        existing.putAll(updates);

        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated config overrides - DO NOT EDIT MANUALLY\n");
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        Files.writeString(overrideFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("配置覆盖已写入: {}", overrideFile.toAbsolutePath());
    }

    /**
     * 读取已落盘的配置覆盖（data/application-override.properties）。
     *
     * <p>与 {@link #updateConfig(Map)} 写的是同一份文件，供需要「后台改完、重启后仍生效」的配置项
     * （如 AI 摘要设置）读取初值。文件不存在或读取失败返回空 Map，不影响启动。</p>
     */
    public Map<String, String> getPersistedOverrides() {
        return readOverrides(Path.of(OVERRIDE_DIR).resolve(OVERRIDE_FILE));
    }

    /** 解析 override 文件为 key=value（忽略空行与 # 注释行） */
    private Map<String, String> readOverrides(Path overrideFile) {
        Map<String, String> existing = new LinkedHashMap<>();
        if (!Files.exists(overrideFile)) {
            return existing;
        }
        try {
            for (String line : Files.readAllLines(overrideFile)) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        existing.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("读取配置覆盖文件失败: {} ({})", overrideFile.toAbsolutePath(), e.getMessage());
        }
        return existing;
    }

    // --- Inner DTOs ---

    public static class ConfigGroup {
        public String name;
        public List<ConfigItem> items;

        public ConfigGroup(String name, List<ConfigItem> items) {
            this.name = name;
            this.items = items;
        }
    }

    public static class ConfigItem {
        public String key;
        public String label;
        public String value;
        public boolean secret;
        public boolean restartRequired;
        /** 字段说明（给管理员看的取值提示，可为空） */
        public String description;

        public ConfigItem(String key, String label, String value, boolean secret, boolean restartRequired,
                          String description) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.secret = secret;
            this.restartRequired = restartRequired;
            this.description = description;
        }
    }

    private static class ConfigGroupDef {
        final String name;
        final List<ConfigItemDef> items;

        ConfigGroupDef(String name, List<ConfigItemDef> items) {
            this.name = name;
            this.items = items;
        }
    }

    private static class ConfigItemDef {
        final String key;
        final String label;
        final boolean secret;
        final boolean restartRequired;
        final String description;

        ConfigItemDef(String key, String label, boolean secret, boolean restartRequired) {
            this(key, label, secret, restartRequired, null);
        }

        ConfigItemDef(String key, String label, boolean secret, boolean restartRequired, String description) {
            this.key = key;
            this.label = label;
            this.secret = secret;
            this.restartRequired = restartRequired;
            this.description = description;
        }
    }
}
