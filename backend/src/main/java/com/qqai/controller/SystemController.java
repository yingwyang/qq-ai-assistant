package com.qqai.controller;

import com.qqai.dto.common.ApiResponse;
import com.qqai.security.AuthPrincipal;
import com.qqai.service.AstrBotService;
import com.qqai.service.AuditLogService;
import com.qqai.service.NapCatService;
import com.qqai.service.GptSovitsService;
import com.qqai.service.MediaDownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    @PostMapping("/tts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateVoice(@RequestBody Map<String, Object> request) throws Exception {
        Object textObj = request.get("text");
        if (textObj == null || textObj.toString().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "合成文本不能为空"));
        }
        String text = textObj.toString();
        
        Long userId = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal) {
            userId = ((AuthPrincipal) auth.getPrincipal()).userId();
        }
        
        String audioUrl = gptSovitsService.generateVoice(text, userId);
        Map<String, Object> data = new HashMap<>();
        data.put("audioUrl", audioUrl);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
    @Autowired
    private AstrBotService astrBotService;

    @Autowired
    private NapCatService napCatService;

    @Autowired
    private GptSovitsService gptSovitsService;

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Autowired
    private AuditLogService auditLogService;

    /**
     * 获取当前登录管理员用户名（用于审计）
     */
    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal ap) {
            return ap.username();
        }
        return "unknown";
    }

    /**
     * 按需将 .amr/.silk 语音文件转换为浏览器可播放的 mp3
     */
    @PostMapping("/convert-voice")
    public ResponseEntity<ApiResponse<Map<String, Object>>> convertVoice(@RequestBody Map<String, Object> request) {
        Object pathObj = request.get("path");
        if (pathObj == null || pathObj.toString().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "语音路径不能为空"));
        }
        String mp3Path = mediaDownloadService.convertVoiceToMp3OnDemand(pathObj.toString());
        if (mp3Path != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("audioUrl", mp3Path);
            return ResponseEntity.ok(ApiResponse.success(data));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "语音转码失败"));
        }
    }

    @PostMapping("/start-all")
    public ResponseEntity<ApiResponse<Map<String, String>>> startAllComponents() {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            astrBotService.startAstrBot();
            result.put("astrbot", "started");
            auditLogService.log(operator, "COMPONENT_START", "astrbot", "SUCCESS", "一键启动全部组件");
        } catch (Exception e) {
            result.put("astrbot", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_START", "astrbot", "FAILURE", e.getMessage());
        }

        try {
            napCatService.startNapCat();
            result.put("napcat", "started");
            auditLogService.log(operator, "COMPONENT_START", "napcat", "SUCCESS", "一键启动全部组件");
        } catch (Exception e) {
            result.put("napcat", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_START", "napcat", "FAILURE", e.getMessage());
        }

        try {
            gptSovitsService.startGptSovits();
            result.put("gpt-sovits", "started");
            auditLogService.log(operator, "COMPONENT_START", "gpt-sovits", "SUCCESS", "一键启动全部组件");
        } catch (Exception e) {
            result.put("gpt-sovits", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_START", "gpt-sovits", "FAILURE", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/stop-all")
    public ResponseEntity<ApiResponse<Map<String, String>>> stopAllComponents() {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            astrBotService.stopAstrBot();
            result.put("astrbot", "stopped");
            auditLogService.log(operator, "COMPONENT_STOP", "astrbot", "SUCCESS", "一键停止全部组件");
        } catch (Exception e) {
            result.put("astrbot", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_STOP", "astrbot", "FAILURE", e.getMessage());
        }

        try {
            napCatService.stopNapCat();
            result.put("napcat", "stopped");
            auditLogService.log(operator, "COMPONENT_STOP", "napcat", "SUCCESS", "一键停止全部组件");
        } catch (Exception e) {
            result.put("napcat", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_STOP", "napcat", "FAILURE", e.getMessage());
        }

        try {
            gptSovitsService.stopGptSovits();
            result.put("gpt-sovits", "stopped");
            auditLogService.log(operator, "COMPONENT_STOP", "gpt-sovits", "SUCCESS", "一键停止全部组件");
        } catch (Exception e) {
            result.put("gpt-sovits", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_STOP", "gpt-sovits", "FAILURE", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/qrcode")
    public ResponseEntity<ApiResponse<Map<String, String>>> getNapCatQrCode() throws Exception {
        Map<String, String> result = new HashMap<>();
        String qrCode = napCatService.getLoginQrCode();
        result.put("qrcode", qrCode);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/login-status")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> checkNapCatLoginStatus() {
        Map<String, Boolean> result = new HashMap<>();
        try {
            boolean loggedIn = napCatService.checkLoginStatus();
            result.put("loggedIn", loggedIn);
        } catch (Exception e) {
            result.put("loggedIn", false);
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/qrcode-path")
    public ResponseEntity<ApiResponse<Map<String, String>>> getNapCatQrCodePath() throws Exception {
        Map<String, String> result = new HashMap<>();
        String qrCodePath = napCatService.getQrCodePath();
        result.put("qrcodePath", qrCodePath);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/qrcode-image")
    public ResponseEntity<Resource> getNapCatQrCodeImage() throws Exception {
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
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> healthCheck() {
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
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ========== 分别启动/停止各个组件 ==========

    @PostMapping("/start-astrbot")
    public ResponseEntity<ApiResponse<Map<String, String>>> startAstrBot() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            astrBotService.startAstrBot();
            result.put("status", "started");
            result.put("message", "AstrBot 启动成功");
            auditLogService.log(operator, "COMPONENT_START", "astrbot", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_START", "astrbot", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/stop-astrbot")
    public ResponseEntity<ApiResponse<Map<String, String>>> stopAstrBot() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            astrBotService.stopAstrBot();
            result.put("status", "stopped");
            result.put("message", "AstrBot 停止成功");
            auditLogService.log(operator, "COMPONENT_STOP", "astrbot", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_STOP", "astrbot", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/start-napcat")
    public ResponseEntity<ApiResponse<Map<String, String>>> startNapCat() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            napCatService.startNapCat();
            result.put("status", "started");
            result.put("message", "NapCat 启动成功");
            auditLogService.log(operator, "COMPONENT_START", "napcat", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_START", "napcat", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/napcat/auto-configure")
    public ResponseEntity<ApiResponse<Map<String, Object>>> autoConfigureNapCat() throws Exception {
        Map<String, Object> result = new HashMap<>();
        String operator = currentUsername();
        try {
            napCatService.checkAndAutoConfigureNewQq();
            result.put("configured", true);
            result.put("message", "NapCat 自动配置完成");
            auditLogService.log(operator, "NAPCAT_AUTO_CONFIG", "napcat", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "NAPCAT_AUTO_CONFIG", "napcat", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/stop-napcat")
    public ResponseEntity<ApiResponse<Map<String, String>>> stopNapCat() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            napCatService.stopNapCat();
            result.put("status", "stopped");
            result.put("message", "NapCat 停止成功");
            auditLogService.log(operator, "COMPONENT_STOP", "napcat", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_STOP", "napcat", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/start-gptsovits")
    public ResponseEntity<ApiResponse<Map<String, String>>> startGptSovits() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            gptSovitsService.startGptSovits();
            result.put("status", "started");
            result.put("message", "GPT-SoVITS 启动成功");
            auditLogService.log(operator, "COMPONENT_START", "gpt-sovits", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_START", "gpt-sovits", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/stop-gptsovits")
    public ResponseEntity<ApiResponse<Map<String, String>>> stopGptSovits() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            gptSovitsService.stopGptSovits();
            result.put("status", "stopped");
            result.put("message", "GPT-SoVITS 停止成功");
            auditLogService.log(operator, "COMPONENT_STOP", "gpt-sovits", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_STOP", "gpt-sovits", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/component-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getComponentStatus() {
        Map<String, Object> result = new HashMap<>();

        // AstrBot 监听 6185 端口
        boolean astrbotRunning = isPortOpen("localhost", 6185);
        result.put("astrbot", Map.of("running", astrbotRunning, "status", astrbotRunning ? "running" : "stopped"));

        // NapCat 监听 6099 端口
        boolean napcatRunning = isPortOpen("localhost", 6099);
        result.put("napcat", Map.of("running", napcatRunning, "status", napcatRunning ? "running" : "stopped"));

        // GPT-SoVITS 监听 8000 端口
        boolean gptsovitsRunning = isPortOpen("localhost", 8000);
        result.put("gptsovits", Map.of("running", gptsovitsRunning, "status", gptsovitsRunning ? "running" : "stopped"));

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/webui-url")
    public ResponseEntity<ApiResponse<Map<String, String>>> getNapCatWebUiUrl() {
        Map<String, String> result = new HashMap<>();
        result.put("url", napCatService.getWebUiUrl());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/disk-usage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDiskUsage() throws Exception {
        Map<String, Object> result = new HashMap<>();
        // uploads 目录大小
        String projectRoot = System.getProperty("user.dir");
        String uploadsPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "qq-ai-assistant" + File.separator + "uploads";
        File uploadsDir = new File(uploadsPath).getCanonicalFile();
        long uploadsSize = 0;
        if (uploadsDir.exists() && uploadsDir.isDirectory()) {
            uploadsSize = calculateDirectorySize(uploadsDir);
        }

        // 项目所在盘空间
        File rootDrive = new File(System.getProperty("user.dir")).toPath().getRoot().toFile();
        long totalSpace = rootDrive.getTotalSpace();
        long freeSpace = rootDrive.getFreeSpace();
        long usedSpace = totalSpace - freeSpace;
        double usagePercent = totalSpace > 0 ? (usedSpace * 100.0 / totalSpace) : 0;

        result.put("uploadsSize", uploadsSize);
        result.put("uploadsSizeFormatted", formatBytes(uploadsSize));
        result.put("totalSpace", totalSpace);
        result.put("totalSpaceFormatted", formatBytes(totalSpace));
        result.put("freeSpace", freeSpace);
        result.put("freeSpaceFormatted", formatBytes(freeSpace));
        result.put("usedSpace", usedSpace);
        result.put("usedSpaceFormatted", formatBytes(usedSpace));
        result.put("usagePercent", Math.round(usagePercent * 100) / 100.0);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private long calculateDirectorySize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return size;
        for (File file : files) {
            if (file.isDirectory()) {
                size += calculateDirectorySize(file);
            } else {
                size += file.length();
            }
        }
        return size;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
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
