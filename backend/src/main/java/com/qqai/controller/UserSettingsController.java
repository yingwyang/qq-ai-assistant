package com.qqai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.entity.UserSettings;
import com.qqai.service.UserSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/user")
public class UserSettingsController {

    @Autowired
    private UserSettingsService userSettingsService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取用户设置
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@RequestParam(required = false) String userId) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");

            // 如果提供了 userId，尝试从数据库获取
            if (userId != null && !userId.isEmpty()) {
                Optional<UserSettings> settings = userSettingsService.findByUserId(userId);
                if (settings.isPresent()) {
                    UserSettings s = settings.get();
                    result.putPOJO("data", Map.of(
                        "botName", s.getBotName(),
                        "botAvatar", s.getBotAvatar(),
                        "userAvatar", s.getUserAvatar()
                    ));
                } else {
                    // 返回默认设置
                    result.putPOJO("data", Map.of(
                        "botName", "AstrBot 助手",
                        "botAvatar", "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100",
                        "userAvatar", "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100"
                    ));
                }
            } else {
                // 未提供 userId，返回默认设置
                result.putPOJO("data", Map.of(
                    "botName", "AstrBot 助手",
                    "botAvatar", "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100",
                    "userAvatar", "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100"
                ));
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 保存用户设置
     */
    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> request) {
        try {
            String userId = (String) request.get("userId");
            String botName = (String) request.get("botName");
            String botAvatar = (String) request.get("botAvatar");
            String userAvatar = (String) request.get("userAvatar");

            UserSettings settings = userSettingsService.saveSettings(userId, botName, botAvatar, userAvatar);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.put("message", "设置保存成功");
            result.putPOJO("data", Map.of(
                "botName", settings.getBotName(),
                "botAvatar", settings.getBotAvatar(),
                "userAvatar", settings.getUserAvatar()
            ));

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }
}
