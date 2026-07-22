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

    private static final List<ConfigGroupDef> GROUP_DEFS = List.of(
            new ConfigGroupDef("AstrBot", List.of(
                    new ConfigItemDef("astrbot.api-url", "API 地址", false, false),
                    new ConfigItemDef("astrbot.token", "Token", true, true)
            )),
            new ConfigGroupDef("NapCat", List.of(
                    new ConfigItemDef("napcat.api-url", "API 地址", false, false),
                    new ConfigItemDef("napcat.token", "Token", true, true),
                    new ConfigItemDef("napcat.webhook-token", "Webhook Token", true, true)
            )),
            new ConfigGroupDef("GPT-SoVITS", List.of(
                    new ConfigItemDef("gpt-sovits.api-url", "API 地址", false, false),
                    new ConfigItemDef("gpt-sovits.token", "Token", true, true)
            )),
            new ConfigGroupDef("MinIO", List.of(
                    new ConfigItemDef("minio.endpoint", "Endpoint", false, false),
                    new ConfigItemDef("minio.access-key", "Access Key", true, true),
                    new ConfigItemDef("minio.secret-key", "Secret Key", true, true)
            )),
            new ConfigGroupDef("JWT", List.of(
                    new ConfigItemDef("jwt.secret", "Secret", true, true),
                    new ConfigItemDef("jwt.expiration", "过期时间(ms)", false, true)
            ))
    );

    public List<ConfigGroup> getConfig() {
        List<ConfigGroup> groups = new ArrayList<>();
        for (ConfigGroupDef def : GROUP_DEFS) {
            List<ConfigItem> items = new ArrayList<>();
            for (ConfigItemDef itemDef : def.items) {
                String value = env.getProperty(itemDef.key, "");
                String displayValue = itemDef.secret ? MASK : value;
                items.add(new ConfigItem(itemDef.key, itemDef.label, displayValue, itemDef.secret, itemDef.restartRequired));
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
        Path configDir = Path.of("data");
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        Path overrideFile = configDir.resolve("application-override.properties");

        // Read existing overrides
        Map<String, String> existing = new LinkedHashMap<>();
        if (Files.exists(overrideFile)) {
            for (String line : Files.readAllLines(overrideFile)) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        existing.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            }
        }

        existing.putAll(updates);

        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated config overrides - DO NOT EDIT MANUALLY\n");
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        Files.writeString(overrideFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("配置覆盖已写入: {}", overrideFile.toAbsolutePath());
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

        public ConfigItem(String key, String label, String value, boolean secret, boolean restartRequired) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.secret = secret;
            this.restartRequired = restartRequired;
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

        ConfigItemDef(String key, String label, boolean secret, boolean restartRequired) {
            this.key = key;
            this.label = label;
            this.secret = secret;
            this.restartRequired = restartRequired;
        }
    }
}
