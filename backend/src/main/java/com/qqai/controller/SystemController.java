package com.qqai.controller;

import com.qqai.common.RateLimiterService;
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
            audioUrl = ttsProvider.synthesize(text, userId, character);
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
        data.put("character", ttsProvider.currentCharacter(userId));
        data.put("cost", ttsResult.getCost());
        data.put("balance", ttsResult.getBalanceAfter());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 获取可用 TTS 角色列表
     */
    @GetMapping("/tts/characters")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listTtsCharacters() {
        List<Map<String, String>> characters = ttsProvider.listCharacters();
        Long userId = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal) {
            userId = ((AuthPrincipal) auth.getPrincipal()).userId();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("characters", characters);
        data.put("current", ttsProvider.currentCharacter(userId));
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
            ttsProvider.switchCharacter(userId, character);
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
    private com.qqai.component.ComponentOrchestrator componentOrchestrator;

    @Autowired
    private com.qqai.capability.im.ImGateway imGateway;

    @Autowired
    private com.qqai.capability.tts.TtsProvider ttsProvider;

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private RateLimiterService rateLimiterService;

    /** 二维码接口限流：同一用户每分钟最多 20 次（正常使用是打开弹窗/点刷新，远低于此值） */
    private static final int QR_MAX_PER_MINUTE = 20;

    /**
     * 二维码接口守卫：区分「登录」与「换号」。
     *
     * <p>产品约束：扫码登录 QQ 是普通用户也要用的功能，不能整体收成 ADMIN
     * （2026-09-24 曾收成 ADMIN-only，直接导致普通用户登录不了 QQ）。</p>
     *
     * <p>但两者风险不同：机器人<b>未登录</b> QQ 时拉二维码 = 正常登录（放行）；
     * 机器人<b>已登录</b>时再拉二维码 = 顶掉当前账号换号，属于敏感操作 → 仅管理员。
     * 顺带补上审计与限流：此前这三个接口既无痕迹也无频次约束，出事后无从追查。</p>
     *
     * @param action       审计动作名
     * @param auditSuccess 成功是否也记审计（仅 /qrcode：它会触发 NapCat 重新生成二维码，是顶号起点）
     * @return null 表示放行，否则返回应直接回给前端的响应
     */
    private ResponseEntity<?> guardQrCodeAccess(String action, boolean auditSuccess) {
        String actor = currentUsername();

        if (!rateLimiterService.isAllowed("napcat-qrcode:" + actor, QR_MAX_PER_MINUTE, 1)) {
            log.warn("【安全】用户 {} 二维码接口调用过于频繁，已限流", actor);
            auditLogService.log(actor, action, "napcat", "FAIL", "二维码接口调用过于频繁");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(429, "二维码获取过于频繁，请稍后重试"));
        }

        boolean loggedIn = isNapCatLoggedIn();
        if (loggedIn && !securityHelper.isAdmin()) {
            log.warn("【安全】机器人已登录 QQ，普通用户 {} 尝试重新获取登录二维码（换号），已拒绝", actor);
            auditLogService.log(actor, action, "napcat", "FAIL",
                    "机器人已登录 QQ，更换登录账号需管理员权限");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "机器人当前已登录 QQ，更换登录账号需要管理员权限"));
        }

        if (auditSuccess) {
            auditLogService.log(actor, action, "napcat", "SUCCESS",
                    loggedIn ? "已登录状态下重新生成二维码（管理员换号）" : "获取登录二维码（机器人未登录）");
        }
        return null;
    }

    /**
     * NapCat 当前是否已登录 QQ。
     *
     * <p>刻意不加缓存：这是「是否允许换号」的判定依据，缓存会让判定滞后（机器人刚登录后仍可能
     * 被普通用户顶号）。该调用只打本机 NapCat，且本接口已按用户限流，代价可接受。</p>
     *
     * <p>判定失败按「已登录」从严处理（fail-closed）：宁可让普通用户去找管理员，也不放行顶号。</p>
     */
    private boolean isNapCatLoggedIn() {
        try {
            return imGateway.checkLoginStatus();
        } catch (Exception e) {
            log.warn("查询 NapCat 登录状态失败，按已登录从严处理: {}", e.getMessage());
            return true;
        }
    }

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

    @Deprecated
    @PostMapping("/start-all")
    public ResponseEntity<ApiResponse<Map<String, String>>> startAllComponents() {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            componentOrchestrator.start("astrbot", false);
            result.put("astrbot", "started");
            auditLogService.log(operator, "COMPONENT_START", "astrbot", "SUCCESS", "一键启动全部组件");
        } catch (Exception e) {
            result.put("astrbot", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_START", "astrbot", "FAILURE", e.getMessage());
        }

        try {
            componentOrchestrator.start("napcat", false);
            result.put("napcat", "started");
            auditLogService.log(operator, "COMPONENT_START", "napcat", "SUCCESS", "一键启动全部组件");
        } catch (Exception e) {
            result.put("napcat", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_START", "napcat", "FAILURE", e.getMessage());
        }

        try {
            componentOrchestrator.start("gpt-sovits", false);
            result.put("gpt-sovits", "started");
            auditLogService.log(operator, "COMPONENT_START", "gpt-sovits", "SUCCESS", "一键启动全部组件");
        } catch (Exception e) {
            result.put("gpt-sovits", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_START", "gpt-sovits", "FAILURE", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Deprecated
    @PostMapping("/stop-all")
    public ResponseEntity<ApiResponse<Map<String, String>>> stopAllComponents() {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            componentOrchestrator.stop("astrbot", true);
            result.put("astrbot", "stopped");
            auditLogService.log(operator, "COMPONENT_STOP", "astrbot", "SUCCESS", "一键停止全部组件");
        } catch (Exception e) {
            result.put("astrbot", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_STOP", "astrbot", "FAILURE", e.getMessage());
        }

        try {
            componentOrchestrator.stop("napcat", true);
            result.put("napcat", "stopped");
            auditLogService.log(operator, "COMPONENT_STOP", "napcat", "SUCCESS", "一键停止全部组件");
        } catch (Exception e) {
            result.put("napcat", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_STOP", "napcat", "FAILURE", e.getMessage());
        }

        try {
            componentOrchestrator.stop("gpt-sovits", true);
            result.put("gpt-sovits", "stopped");
            auditLogService.log(operator, "COMPONENT_STOP", "gpt-sovits", "SUCCESS", "一键停止全部组件");
        } catch (Exception e) {
            result.put("gpt-sovits", "error: " + e.getMessage());
            auditLogService.log(operator, "COMPONENT_STOP", "gpt-sovits", "FAILURE", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/qrcode")
    public ResponseEntity<?> getNapCatQrCode() throws Exception {
        // 已登录状态下重新拉二维码 = 换号顶号，仅管理员；未登录状态普通用户可正常登录
        ResponseEntity<?> denied = guardQrCodeAccess("NAPCAT_QRCODE_FETCH", true);
        if (denied != null) {
            return denied;
        }
        Map<String, String> result = new HashMap<>();
        String qrCode = imGateway.getLoginQrCode();
        result.put("qrcode", qrCode);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/login-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkNapCatLoginStatus() {
        Map<String, Object> result = new HashMap<>();
        try {
            com.qqai.capability.im.ImLoginInfo info = imGateway.loginInfo();
            result.put("loggedIn", info != null);
            if (info != null) {
                result.put("qq", String.valueOf(info.qq()));
                result.put("nickname", info.nickname());
            }
        } catch (Exception e) {
            result.put("loggedIn", false);
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/qrcode-path")
    public ResponseEntity<?> getNapCatQrCodePath() throws Exception {
        ResponseEntity<?> denied = guardQrCodeAccess("NAPCAT_QRCODE_PATH", false);
        if (denied != null) {
            return denied;
        }
        Map<String, String> result = new HashMap<>();
        String qrCodePath = imGateway.getQrCodePath();
        result.put("qrcodePath", qrCodePath);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/qrcode-image")
    public ResponseEntity<?> getNapCatQrCodeImage() throws Exception {
        ResponseEntity<?> denied = guardQrCodeAccess("NAPCAT_QRCODE_IMAGE", false);
        if (denied != null) {
            return denied;
        }
        String qrCodePath = imGateway.getQrCodePath();
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
            imGateway.checkLoginStatus();
            result.put("napcat", "available");
        } catch (Exception e) {
            result.put("napcat", "unavailable");
            result.put("napcatError", e.getMessage());
        }
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ========== 分别启动/停止各个组件 ==========

    @Deprecated
    @PostMapping("/start-astrbot")
    public ResponseEntity<ApiResponse<Map<String, String>>> startAstrBot() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            componentOrchestrator.start("astrbot", false);
            result.put("status", "started");
            result.put("message", "AstrBot 启动成功");
            auditLogService.log(operator, "COMPONENT_START", "astrbot", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_START", "astrbot", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Deprecated
    @PostMapping("/stop-astrbot")
    public ResponseEntity<ApiResponse<Map<String, String>>> stopAstrBot() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            componentOrchestrator.stop("astrbot", true);
            result.put("status", "stopped");
            result.put("message", "AstrBot 停止成功");
            auditLogService.log(operator, "COMPONENT_STOP", "astrbot", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_STOP", "astrbot", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Deprecated
    @PostMapping("/start-napcat")
    public ResponseEntity<ApiResponse<Map<String, String>>> startNapCat() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            componentOrchestrator.start("napcat", false);
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
            imGateway.checkAndAutoConfigureNewQq();
            result.put("configured", true);
            result.put("message", "NapCat 自动配置完成");
            auditLogService.log(operator, "NAPCAT_AUTO_CONFIG", "napcat", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "NAPCAT_AUTO_CONFIG", "napcat", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Deprecated
    @PostMapping("/stop-napcat")
    public ResponseEntity<ApiResponse<Map<String, String>>> stopNapCat() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            componentOrchestrator.stop("napcat", true);
            result.put("status", "stopped");
            result.put("message", "NapCat 停止成功");
            auditLogService.log(operator, "COMPONENT_STOP", "napcat", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_STOP", "napcat", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Deprecated
    @PostMapping("/start-gptsovits")
    public ResponseEntity<ApiResponse<Map<String, String>>> startGptSovits() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            componentOrchestrator.start("gpt-sovits", false);
            result.put("status", "started");
            result.put("message", "GPT-SoVITS 启动成功");
            auditLogService.log(operator, "COMPONENT_START", "gpt-sovits", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_START", "gpt-sovits", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Deprecated
    @PostMapping("/stop-gptsovits")
    public ResponseEntity<ApiResponse<Map<String, String>>> stopGptSovits() throws Exception {
        Map<String, String> result = new HashMap<>();
        String operator = currentUsername();
        try {
            componentOrchestrator.stop("gpt-sovits", true);
            result.put("status", "stopped");
            result.put("message", "GPT-SoVITS 停止成功");
            auditLogService.log(operator, "COMPONENT_STOP", "gpt-sovits", "SUCCESS", null);
        } catch (Exception e) {
            auditLogService.log(operator, "COMPONENT_STOP", "gpt-sovits", "FAILURE", e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Deprecated
    @GetMapping("/component-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getComponentStatus() {
        Map<String, Object> result = new HashMap<>();
        for (com.qqai.component.ComponentModel.ComponentView view : componentOrchestrator.list()) {
            String key = view.legacyKey == null || view.legacyKey.isBlank() ? view.id : view.legacyKey;
            Map<String, Object> item = new HashMap<>();
            item.put("running", view.running);
            item.put("status", view.state == null ? "stopped" : view.state.toLowerCase(java.util.Locale.ROOT));
            result.put(key, item);
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/napcat/webui-url")
    public ResponseEntity<ApiResponse<Map<String, String>>> getNapCatWebUiUrl() {
        Map<String, String> result = new HashMap<>();
        result.put("url", imGateway.getWebUiUrl());
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
