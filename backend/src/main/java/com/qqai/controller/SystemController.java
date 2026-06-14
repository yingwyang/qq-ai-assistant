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
import java.io.IOException;
import java.net.Socket;
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

    // ========== 分别启动/停止各个组件 ==========

    @PostMapping("/start-astrbot")
    public Map<String, String> startAstrBot() {
        Map<String, String> result = new HashMap<>();
        try {
            astrBotService.startAstrBot();
            result.put("status", "started");
            result.put("message", "AstrBot 启动成功");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/stop-astrbot")
    public Map<String, String> stopAstrBot() {
        Map<String, String> result = new HashMap<>();
        try {
            astrBotService.stopAstrBot();
            result.put("status", "stopped");
            result.put("message", "AstrBot 停止成功");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/start-napcat")
    public Map<String, String> startNapCat(@RequestBody(required = false) Map<String, Object> params) {
        Map<String, String> result = new HashMap<>();
        try {
            boolean autoLogin = params != null && Boolean.TRUE.equals(params.get("autoLogin"));
            napCatService.startNapCat(autoLogin);
            result.put("status", "started");
            result.put("message", "NapCat 启动成功");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/stop-napcat")
    public Map<String, String> stopNapCat() {
        Map<String, String> result = new HashMap<>();
        try {
            napCatService.stopNapCat();
            result.put("status", "stopped");
            result.put("message", "NapCat 停止成功");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/start-gptsovits")
    public Map<String, String> startGptSovits() {
        Map<String, String> result = new HashMap<>();
        try {
            gptSovitsService.startGptSovits();
            result.put("status", "started");
            result.put("message", "GPT-SoVITS 启动成功");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/stop-gptsovits")
    public Map<String, String> stopGptSovits() {
        Map<String, String> result = new HashMap<>();
        try {
            gptSovitsService.stopGptSovits();
            result.put("status", "stopped");
            result.put("message", "GPT-SoVITS 停止成功");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/component-status")
    public Map<String, Object> getComponentStatus() {
        Map<String, Object> result = new HashMap<>();

        // AstrBot 监听 6185 端口
        boolean astrbotRunning = isPortOpen("localhost", 6185);
        result.put("astrbot", Map.of("running", astrbotRunning, "status", astrbotRunning ? "running" : "stopped"));

        // NapCat 监听 6099 端口
        boolean napcatRunning = isPortOpen("localhost", 6099);
        result.put("napcat", Map.of("running", napcatRunning, "status", napcatRunning ? "running" : "stopped"));

        // GPT-SoVITS 监听 7860 端口
        boolean gptsovitsRunning = isPortOpen("localhost", 7860);
        result.put("gptsovits", Map.of("running", gptsovitsRunning, "status", gptsovitsRunning ? "running" : "stopped"));

        return result;
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}