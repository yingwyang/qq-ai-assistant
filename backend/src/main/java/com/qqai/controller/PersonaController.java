package com.qqai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/persona")
public class PersonaController {

    private static final Logger log = LoggerFactory.getLogger(PersonaController.class);

    @Value("${astrbot.data-path:./Astrbot/data}")
    private String astrbotDataPath;

    @Value("${astrbot.api-url:http://localhost:6185}")
    private String astrBotApiUrl;

    @Value("${astrbot.token:}")
    private String astrBotToken;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Connection getConnection() throws SQLException {
        String dbPath = astrbotDataPath + "/data_v4.db";
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    @GetMapping("/list")
    public ResponseEntity<?> listPersonas() {
        List<Map<String, Object>> personas = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, persona_id, system_prompt, begin_dialogs, tools, skills, custom_error_message, folder_id, sort_order, created_at, updated_at FROM personas ORDER BY sort_order, id")) {
            while (rs.next()) {
                Map<String, Object> p = new HashMap<>();
                p.put("id", rs.getInt("id"));
                p.put("personaId", rs.getString("persona_id"));
                p.put("systemPrompt", rs.getString("system_prompt"));
                p.put("beginDialogs", parseJson(rs.getString("begin_dialogs")));
                p.put("tools", parseJson(rs.getString("tools")));
                p.put("skills", parseJson(rs.getString("skills")));
                p.put("customErrorMessage", rs.getString("custom_error_message"));
                p.put("folderId", rs.getString("folder_id"));
                p.put("sortOrder", rs.getInt("sort_order"));
                p.put("createdAt", rs.getString("created_at"));
                p.put("updatedAt", rs.getString("updated_at"));
                personas.add(p);
            }
            return ResponseEntity.ok(personas);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getPersona(@PathVariable int id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, persona_id, system_prompt, begin_dialogs, tools, skills, custom_error_message, folder_id, sort_order, created_at, updated_at FROM personas WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> p = new HashMap<>();
                p.put("id", rs.getInt("id"));
                p.put("personaId", rs.getString("persona_id"));
                p.put("systemPrompt", rs.getString("system_prompt"));
                p.put("beginDialogs", parseJson(rs.getString("begin_dialogs")));
                p.put("tools", parseJson(rs.getString("tools")));
                p.put("skills", parseJson(rs.getString("skills")));
                p.put("customErrorMessage", rs.getString("custom_error_message"));
                p.put("folderId", rs.getString("folder_id"));
                p.put("sortOrder", rs.getInt("sort_order"));
                p.put("createdAt", rs.getString("created_at"));
                p.put("updatedAt", rs.getString("updated_at"));
                return ResponseEntity.ok(p);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ===== 以下写操作修改 AstrBot 全局人格配置,仅限管理员 =====

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createPersona(@RequestBody Map<String, Object> request) {
        String personaId = (String) request.get("personaId");
        String systemPrompt = (String) request.getOrDefault("systemPrompt", "");
        String customErrorMessage = (String) request.getOrDefault("customErrorMessage", "");
        Object beginDialogs = request.get("beginDialogs");
        Object tools = request.get("tools");
        Object skills = request.get("skills");
        String folderId = (String) request.getOrDefault("folderId", null);

        if (personaId == null || personaId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "人格ID不能为空"));
        }

        String now = LocalDateTime.now().toString();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO personas (persona_id, system_prompt, begin_dialogs, tools, skills, custom_error_message, folder_id, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, personaId);
            ps.setString(2, systemPrompt);
            ps.setString(3, toJson(beginDialogs));
            ps.setString(4, toJson(tools));
            ps.setString(5, toJson(skills));
            ps.setString(6, customErrorMessage);
            ps.setString(7, folderId);
            ps.setInt(8, 0);
            ps.setString(9, now);
            ps.setString(10, now);
            ps.executeUpdate();
            return ResponseEntity.ok(Map.of("success", true, "message", "人格创建成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updatePersona(@PathVariable int id, @RequestBody Map<String, Object> request) {
        String personaId = (String) request.get("personaId");
        String systemPrompt = (String) request.getOrDefault("systemPrompt", "");
        String customErrorMessage = (String) request.getOrDefault("customErrorMessage", "");
        Object beginDialogs = request.get("beginDialogs");
        Object tools = request.get("tools");
        Object skills = request.get("skills");

        String now = LocalDateTime.now().toString();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE personas SET persona_id = ?, system_prompt = ?, begin_dialogs = ?, tools = ?, skills = ?, custom_error_message = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, personaId);
            ps.setString(2, systemPrompt);
            ps.setString(3, toJson(beginDialogs));
            ps.setString(4, toJson(tools));
            ps.setString(5, toJson(skills));
            ps.setString(6, customErrorMessage);
            ps.setString(7, now);
            ps.setInt(8, id);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                return ResponseEntity.ok(Map.of("success", true, "message", "人格更新成功"));
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deletePersona(@PathVariable int id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM personas WHERE id = ?")) {
            ps.setInt(1, id);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                return ResponseEntity.ok(Map.of("success", true, "message", "人格删除成功"));
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/set-default")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setDefaultPersona(@RequestBody Map<String, Object> request) {
        String personaId = (String) request.get("personaId");
        if (personaId == null || personaId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "人格ID不能为空"));
        }

        try (Connection conn = getConnection()) {
            // 确保is_default字段存在
            ensureIsDefaultColumn(conn);
            
            // 先将所有人格的is_default设置为0
            try (PreparedStatement ps = conn.prepareStatement("UPDATE personas SET is_default = 0")) {
                ps.executeUpdate();
            }
            // 再将指定人格设置为默认
            try (PreparedStatement ps = conn.prepareStatement("UPDATE personas SET is_default = 1 WHERE persona_id = ?")) {
                ps.setString(1, personaId);
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    // 同时更新 AstrBot 的 provider_settings 配置
                    updateAstrBotDefaultPersonality(personaId);
                    return ResponseEntity.ok(Map.of("success", true, "message", "默认人格设置成功"));
                } else {
                    return ResponseEntity.badRequest().body(Map.of("error", "未找到指定的人格"));
                }
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private void updateAstrBotDefaultPersonality(String personaId) {
        try {
            log.debug("【调试】开始更新AstrBot默认人格配置，personaId: {}", personaId);

            // AstrBot 使用 JSON 配置文件存储默认人格
            String configPath = astrbotDataPath + "/cmd_config.json";
            File configFile = new File(configPath);

            if (!configFile.exists()) {
                log.debug("【调试】配置文件不存在: {}", configPath);
                return;
            }

            // 读取配置文件
            String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            ObjectNode json = (ObjectNode) objectMapper.readTree(content);

            // 更新默认人格
            ObjectNode providerSettings = (ObjectNode) json.get("provider_settings");
            if (providerSettings != null) {
                JsonNode oldPersonality = providerSettings.get("default_personality");
                providerSettings.put("default_personality", personaId);
                log.debug("【调试】默认人格从 '{}' 更改为 '{}'", oldPersonality != null ? oldPersonality.asText() : null, personaId);

                // 写回配置文件
                java.nio.file.Files.write(configFile.toPath(), objectMapper.writeValueAsBytes(json));
                log.debug("【调试】配置文件已更新");

                // 调用 AstrBot API 通知配置已更改
                notifyAstrBotConfigChanged();
            } else {
                log.debug("【调试】provider_settings 不存在");
            }

            log.debug("【调试】更新AstrBot默认人格配置完成");
        } catch (Exception e) {
            // 忽略更新失败，不影响主流程
            log.error("【调试】更新AstrBot默认人格配置失败: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    private void notifyAstrBotConfigChanged() {
        try {

            // 调用 AstrBot 的配置保存 API，传递完整的配置数据
            String url = astrBotApiUrl + "/api/config/save";

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + astrBotToken);
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            // 读取完整的配置文件
            String configPath = astrbotDataPath + "/cmd_config.json";
            String content = new String(java.nio.file.Files.readAllBytes(new File(configPath).toPath()), java.nio.charset.StandardCharsets.UTF_8);
            ObjectNode configJson = (ObjectNode) objectMapper.readTree(content);

            // 构造请求体 - 传递完整的配置
            Map<String, Object> body = new HashMap<>();
            body.put("is_core", true);
            body.put("config", configJson);

            org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(body, headers);

            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            log.debug("【调试】AstrBot 配置保存 API 响应: {}", response.getBody());
        } catch (Exception e) {
            log.error("【调试】调用 AstrBot 配置保存 API 失败: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    @GetMapping("/default")
    public ResponseEntity<?> getDefaultPersona() {
        try (Connection conn = getConnection()) {
            // 确保is_default字段存在
            ensureIsDefaultColumn(conn);
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT persona_id FROM personas WHERE is_default = 1")) {
                if (rs.next()) {
                    return ResponseEntity.ok(Map.of("defaultPersonaId", rs.getString("persona_id")));
                }
                return ResponseEntity.ok(Map.of("defaultPersonaId", null));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private void ensureIsDefaultColumn(Connection conn) {
        try {
            // 检查is_default字段是否存在
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(personas)")) {
                boolean exists = false;
                while (rs.next()) {
                    if ("is_default".equals(rs.getString("name"))) {
                        exists = true;
                        break;
                    }
                }
                // 如果不存在则添加字段
                if (!exists) {
                    try (Statement alterStmt = conn.createStatement()) {
                        alterStmt.executeUpdate("ALTER TABLE personas ADD COLUMN is_default INTEGER DEFAULT 0");
                    }
                }
            }
        } catch (Exception e) {
            // 忽略字段添加错误
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
