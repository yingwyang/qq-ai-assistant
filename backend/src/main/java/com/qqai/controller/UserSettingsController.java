package com.qqai.controller;

import com.alibaba.fastjson2.JSONObject;
import com.qqai.entity.UserSettings;
import com.qqai.repository.UserSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/user")
public class UserSettingsController {

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    /**
     * 获取用户设置
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@RequestParam(required = false) String userId) {
        try {
            JSONObject result = new JSONObject();
            result.put("status", "ok");
            
            // 如果提供了 userId，尝试从数据库获取
            if (userId != null && !userId.isEmpty()) {
                Optional<UserSettings> settings = userSettingsRepository.findByUserId(userId);
                if (settings.isPresent()) {
                    UserSettings s = settings.get();
                    result.put("data", Map.of(
                        "botName", s.getBotName(),
                        "botAvatar", s.getBotAvatar(),
                        "userAvatar", s.getUserAvatar()
                    ));
                } else {
                    // 返回默认设置
                    result.put("data", Map.of(
                        "botName", "AstrBot 助手",
                        "botAvatar", "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100",
                        "userAvatar", "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100"
                    ));
                }
            } else {
                // 未提供 userId，返回默认设置
                result.put("data", Map.of(
                    "botName", "AstrBot 助手",
                    "botAvatar", "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100",
                    "userAvatar", "https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100"
                ));
            }
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            JSONObject error = new JSONObject();
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

            // 如果没有提供 userId，使用默认标识存储全局设置
            if (userId == null || userId.isEmpty()) {
                userId = "default";
            }

            UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElse(new UserSettings());
            
            settings.setUserId(userId);
            if (botName != null) settings.setBotName(botName);
            if (botAvatar != null) settings.setBotAvatar(botAvatar);
            if (userAvatar != null) settings.setUserAvatar(userAvatar);
            
            userSettingsRepository.save(settings);

            JSONObject result = new JSONObject();
            result.put("status", "ok");
            result.put("message", "设置保存成功");
            result.put("data", Map.of(
                "botName", settings.getBotName(),
                "botAvatar", settings.getBotAvatar(),
                "userAvatar", settings.getUserAvatar()
            ));
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }
}
