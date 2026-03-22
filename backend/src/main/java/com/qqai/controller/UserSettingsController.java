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
    public ResponseEntity<?> getSettings(@RequestParam String userQq) {
        try {
            Optional<UserSettings> settings = userSettingsRepository.findByUserQq(userQq);
            
            JSONObject result = new JSONObject();
            result.put("status", "ok");
            
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
            String userQq = (String) request.get("userQq");
            String botName = (String) request.get("botName");
            String botAvatar = (String) request.get("botAvatar");
            String userAvatar = (String) request.get("userAvatar");

            if (userQq == null || userQq.isEmpty()) {
                JSONObject error = new JSONObject();
                error.put("status", "error");
                error.put("message", "用户QQ不能为空");
                return ResponseEntity.ok(error);
            }

            UserSettings settings = userSettingsRepository.findByUserQq(userQq)
                .orElse(new UserSettings());
            
            settings.setUserQq(userQq);
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
