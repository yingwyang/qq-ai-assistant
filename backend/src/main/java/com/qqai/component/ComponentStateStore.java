package com.qqai.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 组件状态持久化（JSON 文件，原子替换；不引入 DB 迁移）。 */
@Component
public class ComponentStateStore {

    private static final Logger log = LoggerFactory.getLogger(ComponentStateStore.class);

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path stateFile;
    private Map<String, ComponentModel.ComponentRecord> records = new LinkedHashMap<>();

    public ComponentStateStore(ComponentProperties properties) {
        Path p = Paths.get(properties.getStateFile());
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(properties.getStateFile());
        }
        this.stateFile = p;
    }

    @PostConstruct
    public void load() {
        try {
            if (Files.exists(stateFile)) {
                Map<String, ComponentModel.ComponentRecord> loaded = mapper.readValue(
                        stateFile.toFile(),
                        mapper.getTypeFactory().constructMapType(Map.class,
                                String.class, ComponentModel.ComponentRecord.class));
                records = new LinkedHashMap<>(loaded);
            }
            log.info("组件状态已加载: {}", stateFile);
        } catch (Exception e) {
            log.warn("组件状态文件损坏，已重置: {} ({})", stateFile, e.getMessage());
            try {
                Files.move(stateFile, stateFile.resolveSibling(stateFile.getFileName() + ".corrupt-"
                        + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignore) {
                // ignore
            }
            records = new LinkedHashMap<>();
        }
    }

    public synchronized ComponentModel.ComponentRecord get(String id) {
        return records.computeIfAbsent(id, k -> new ComponentModel.ComponentRecord());
    }

    public synchronized void put(String id, ComponentModel.ComponentRecord record) {
        record.updatedAt = OffsetDateTime.now().toString();
        records.put(id, record);
        persist();
    }

    public synchronized Map<String, ComponentModel.ComponentRecord> snapshot() {
        Map<String, ComponentModel.ComponentRecord> copy = new LinkedHashMap<>();
        records.forEach((k, v) -> copy.put(k, v.copy()));
        return copy;
    }

    private synchronized void persist() {
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), records);
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("写组件状态失败: {}", e.getMessage());
        }
    }
}
