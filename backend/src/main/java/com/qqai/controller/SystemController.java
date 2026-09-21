package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.security.AuthPrincipal;
import com.qqai.service.AstrBotService;
import com.qqai.service.AuditLogService;
import com.qqai.service.CreditService;
import com.qqai.service.NapCatService;
import com.qqai.service.GptSovitsService;
import com.qqai.service.MediaDownloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    @PostMapping("/tts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateVoice(@RequestBody Map<String, Object> request) throws Exception {
        Object textObj = request.get("text");
        if (textObj == null || textObj.toString().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "合成文本不能为空"));
        }
        String text = textObj.toString();
        // 可选角色参数
        String character = request.get("character") != null ? request.get("character").toString() : null;

        Long userId = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal) {
            userId = ((AuthPrincipal) auth.getPrincipal()).userId();
        }

        // 调用 TTS 前先扣费（余额不足会抛 BizException）
        boolean isAdmin = securityHelper.isAdmin();
        // 本次合成的幂等键：既写入扣费流水的 relatedId，也用于合成失败时精确退费
        String requestKey = "tts-" + java.util.UUID.randomUUID();
        CreditService.CreditCostResult ttsResult = creditService.spendForTts(userId, text, character, isAdmin, requestKey);

        String audioUrl;
        try {
            audioUrl = gptSovitsService.generateVoice(text, userId, character);
        } catch (RuntimeException e) {
            // 合成失败必须把刚扣的积分退回去（按 requestKey 精确匹配、幂等），否则用户为没拿到的语音付费
            if (userId != null && ttsResult != null && ttsResult.getCost() > 0) {
                try {
                    creditService.refundForTts(userId, requestKey, "TTS 合成失败");
                } catch (Exception refundError) {
                    log.error("TTS 失败退费失败 userId={} requestKey={}: {}", userId, requestKey, refundError.getMessage());
                }
            }
            throw e;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("audioUrl", audioUrl);
        data.put("character", gptSovitsService.getUserCharacter(userId));
        data.put("cost", ttsResult.getCost());
        data.put("balance", ttsResult.getBalanceAfter());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 获取可用 TTS 角色列表
     */
    @GetMapping("/tts/characters")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listTtsCharacters() {
        List<Map<String, String>> characters = gptSovitsService.listCharacters();
        Long userId = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal) {
            userId = ((AuthPrincipal) auth.getPrincipal()).userId();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("characters", characters);
        data.put("current", gptSovitsService.getUserCharacter(userId));
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 切换 TTS 角色（为当前用户设置偏好）
     */
    @PostMapping("/tts/switch-character")
    public ResponseEntity<ApiResponse<Map<String, Object>>> switchTtsCharacter(@RequestBody Map<String, Object> request) {
        Object charObj = request.get("character");
        if (charObj == null || charObj.toString().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "角色名不能为空"));
        }
        String character = charObj.toString();

        Long userId = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal) {
            userId = ((AuthPrincipal) auth.getPrincipal()).userId();
        }

        try {
            gptSovitsService.setCharacterForUser(userId, character);
            Map<String, Object> data = new HashMap<>();
            data.put("character", character);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (IllegalArgumentException e) {
            // 参数类错误原样上抛：GlobalExceptionHandler 会映射为 400 BAD_ARGUMENT + 原始校验文案
            throw e;
        } catch (Exception e) {
            log.error("切换角色失败 userId={} character={}", userId, character, e);
            throw new com.qqai.exception.BizException(500, "TTS_CHARACTER_SWITCH_FAILED", "切换角色失败，请稍后重试");
        }
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

    @Autowired
    private CreditService creditService;

    @Autowired
    private SecurityHelper securityHelper;

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
        // uploads 目录大小（backend/uploads/，相对于 user.dir）
        String projectRoot = System.getProperty("user.dir");
        File uploadsDir = new File(projectRoot, "uploads").getCanonicalFile();
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
            // 端口未开/连接被拒是"组件未运行"的正常信号，用 debug 级别留痕（不污染 INFO 日志）
            log.debug("端口探测失败（按未运行处理） {}:{} - {}", host, port, e.toString());
            return false;
        }
    }
}
