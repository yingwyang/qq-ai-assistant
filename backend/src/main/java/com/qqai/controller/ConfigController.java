package com.qqai.controller;

import com.qqai.service.ConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        List<ConfigService.ConfigGroup> groups = configService.getConfig();
        return ResponseEntity.ok(groups);
    }

    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, String> updates) {
        List<String> restartRequired = configService.updateConfig(updates);
        Map<String, Object> result = Map.of(
                "message", "配置更新成功",
                "restartRequired", restartRequired
        );
        return ResponseEntity.ok(result);
    }
}
