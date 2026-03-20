package com.qqai.controller;

import com.qqai.service.AstrBotService;
import com.qqai.service.NapCatService;
import com.qqai.service.GptSovitsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    @Autowired
    private AstrBotService astrBotService;

    @Autowired
    private NapCatService napCatService;

    @Autowired
    private GptSovitsService gptSovitsService;

    @PostMapping("/start-all")
    public Map<String, String> startAllComponents() {
        Map<String, String> result = new HashMap<>();
        try {
            astrBotService.startAstrBot();
            result.put("astrbot", "started");
        } catch (Exception e) {
            result.put("astrbot", "error: " + e.getMessage());
        }

        try {
            napCatService.startNapCat();
            result.put("napcat", "started");
        } catch (Exception e) {
            result.put("napcat", "error: " + e.getMessage());
        }

        try {
            gptSovitsService.startGptSovits();
            result.put("gpt-sovits", "started");
        } catch (Exception e) {
            result.put("gpt-sovits", "error: " + e.getMessage());
        }

        return result;
    }

    @PostMapping("/stop-all")
    public Map<String, String> stopAllComponents() {
        Map<String, String> result = new HashMap<>();
        try {
            astrBotService.stopAstrBot();
            result.put("astrbot", "stopped");
        } catch (Exception e) {
            result.put("astrbot", "error: " + e.getMessage());
        }

        try {
            napCatService.stopNapCat();
            result.put("napcat", "stopped");
        } catch (Exception e) {
            result.put("napcat", "error: " + e.getMessage());
        }

        try {
            gptSovitsService.stopGptSovits();
            result.put("gpt-sovits", "stopped");
        } catch (Exception e) {
            result.put("gpt-sovits", "error: " + e.getMessage());
        }

        return result;
    }

    @GetMapping("/napcat/qrcode")
    public Map<String, String> getNapCatQrCode() {
        Map<String, String> result = new HashMap<>();
        try {
            String qrCode = napCatService.getLoginQrCode();
            result.put("qrcode", qrCode);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/napcat/login-status")
    public Map<String, Boolean> checkNapCatLoginStatus() {
        Map<String, Boolean> result = new HashMap<>();
        try {
            boolean loggedIn = napCatService.checkLoginStatus();
            result.put("loggedIn", loggedIn);
        } catch (Exception e) {
            result.put("loggedIn", false);
        }
        return result;
    }

    @GetMapping("/napcat/qrcode-path")
    public Map<String, String> getNapCatQrCodePath() {
        Map<String, String> result = new HashMap<>();
        try {
            String qrCodePath = napCatService.getQrCodePath();
            result.put("qrcodePath", qrCodePath);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/napcat/qrcode-image")
    public ResponseEntity<Resource> getNapCatQrCodeImage() {
        try {
            String qrCodePath = napCatService.getQrCodePath();
            File file = new File(qrCodePath);
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }
            Path path = Paths.get(qrCodePath);
            Resource resource = new UrlResource(path.toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("timestamp", System.currentTimeMillis());
        
        // 检查 NapCat 服务是否可访问
        try {
            napCatService.checkLoginStatus();
            result.put("napcat", "available");
        } catch (Exception e) {
            result.put("napcat", "unavailable");
            result.put("napcatError", e.getMessage());
        }
        
        return result;
    }
}