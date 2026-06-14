package com.qqai.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/persona")
public class PersonaController {

    @Value("${astrbot.data-path:D:/ai/Documents/qq-web/Astrbot/data}")
    private String astrbotDataPath;

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

    @PostMapping
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
    public ResponseEntity<?> setDefaultPersona(@RequestBody Map<String, Object> request) {
        String personaId = (String) request.get("personaId");
        if (personaId == null || personaId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "人格ID不能为空"));
        }

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE preferences SET value = ? WHERE key = 'default_personality'")) {
            ps.setString(1, personaId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                // 如果不存在则插入
                try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO preferences (key, value) VALUES ('default_personality', ?)")) {
                    insert.setString(1, personaId);
                    insert.executeUpdate();
                }
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "默认人格设置成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/default")
    public ResponseEntity<?> getDefaultPersona() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT value FROM preferences WHERE key = 'default_personality'")) {
            if (rs.next()) {
                return ResponseEntity.ok(Map.of("defaultPersonaId", rs.getString("value")));
            }
            return ResponseEntity.ok(Map.of("defaultPersonaId", null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return com.alibaba.fastjson.JSON.parse(json);
        } catch (Exception e) {
            return json;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return com.alibaba.fastjson.JSON.toJSONString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
