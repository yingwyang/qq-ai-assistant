package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.BotView;
import com.qqai.entity.Bot;
import com.qqai.dto.common.ApiResponse;
import com.qqai.exception.BizException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.qqai.capability.im.ImGateway;
import com.qqai.service.BotAgentClient;
import com.qqai.service.BotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 用户自己的机器人：创建、查看、改名、删除。启停/扫码在后续阶段接入。 */
@RestController
@RequestMapping("/api/bots")
public class BotController {

    private final BotService botService;
    private final BotAgentClient agentClient;
    private final ImGateway imGateway;
    private final SecurityHelper securityHelper;

    public BotController(BotService botService, BotAgentClient agentClient,
                         ImGateway imGateway, SecurityHelper securityHelper) {
        this.botService = botService;
        this.agentClient = agentClient;
        this.imGateway = imGateway;
        this.securityHelper = securityHelper;
    }

    @GetMapping
    public ApiResponse<List<BotView>> list(@RequestParam(name = "all", defaultValue = "false") boolean all) {
        Long userId = securityHelper.requireCurrentUserId();
        boolean admin = securityHelper.isAdmin();
        List<Bot> bots = (all && admin) ? botService.listAll() : botService.listOwned(userId);
        return ApiResponse.success(bots.stream().map(BotView::of).toList());
    }

    @PostMapping
    public ApiResponse<BotView> create(@Valid @RequestBody CreateBotRequest request) {
        Long userId = securityHelper.requireCurrentUserId();
        Bot bot = botService.create(userId, request.name(), request.runtimeType(),
                request.onebotUrl(), request.webuiUrl(), securityHelper.getCurrentUsername());
        return ApiResponse.success(BotView.of(bot));
    }

    @GetMapping("/{id}")
    public ApiResponse<BotView> get(@PathVariable Long id) {
        Long userId = securityHelper.requireCurrentUserId();
        return ApiResponse.success(BotView.of(botService.getOwned(id, userId, securityHelper.isAdmin())));
    }

    @PatchMapping("/{id}")
    public ApiResponse<BotView> rename(@PathVariable Long id, @Valid @RequestBody RenameBotRequest request) {
        Long userId = securityHelper.requireCurrentUserId();
        Bot bot = botService.rename(id, userId, securityHelper.isAdmin(), request.name(),
                securityHelper.getCurrentUsername());
        return ApiResponse.success(BotView.of(bot));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long userId = securityHelper.requireCurrentUserId();
        botService.delete(id, userId, securityHelper.isAdmin(), securityHelper.getCurrentUsername());
        return ApiResponse.<Void>success(null);
    }

    @PatchMapping("/{id}/runtime")
    public ApiResponse<BotView> updateRuntime(@PathVariable Long id,
                                              @Valid @RequestBody RuntimeRequest request) {
        Long userId = securityHelper.requireCurrentUserId();
        Bot bot = botService.updateRuntime(id, userId, securityHelper.isAdmin(), request.runtimeType(),
                request.onebotUrl(), request.webuiUrl(), request.agentUrl(), securityHelper.getCurrentUsername());
        return ApiResponse.success(BotView.of(bot));
    }

    /** 远程 agent 心跳；用每 bot token 认证，不走 JWT（SecurityConfig 已放行该路径）。 */
    @PostMapping("/{id}/heartbeat")
    public ApiResponse<Map<String, Object>> heartbeat(@PathVariable Long id,
                                                      @RequestHeader(value = "X-Agent-Token", required = false) String token) {
        Bot bot = botService.heartbeat(id, token);
        return ApiResponse.success(Map.of(
                "status", "ok",
                "botId", bot.getId(),
                "receivedAt", String.valueOf(bot.getLastHeartbeatAt())));
    }

    /** 远程 agent 注册/更新自身地址（X-Agent-Token，免 JWT）。 */
    @PostMapping("/{id}/agent/register")
    public ApiResponse<BotView> registerAgent(@PathVariable Long id,
                                              @RequestHeader(value = "X-Agent-Token", required = false) String token,
                                              @RequestBody AgentRegisterRequest request) {
        Bot bot = botService.registerAgent(id, token, request.onebotUrl(), request.webuiUrl(),
                request.agentUrl());
        return ApiResponse.success(BotView.of(bot));
    }

    /** 二维码：默认本机 bot 读 NapCat 缓存；远程 bot 由 agent 提供。 */
    @GetMapping("/{id}/qrcode-image")
    public ResponseEntity<byte[]> qrcodeImage(@PathVariable Long id) throws Exception {
        Long userId = securityHelper.requireCurrentUserId();
        Bot bot = botService.getOwned(id, userId, securityHelper.isAdmin());
        if ("REMOTE".equals(bot.getRuntimeType())) {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(agentClient.fetchQrCode(bot));
        }
        if (bot.getId() == null || bot.getId() != 1L) {
            throw new BizException(409, "本机多实例尚未支持，请使用 REMOTE agent");
        }
        java.nio.file.Path path = java.nio.file.Paths.get(imGateway.getQrCodePath());
        if (!java.nio.file.Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(java.nio.file.Files.readAllBytes(path));
    }

    /** 登录态：默认本机 bot 走 OneBot；远程 bot 由 agent /status 返回。 */
    @GetMapping("/{id}/login-status")
    public ApiResponse<Object> loginStatus(@PathVariable Long id) throws Exception {
        Long userId = securityHelper.requireCurrentUserId();
        Bot bot = botService.getOwned(id, userId, securityHelper.isAdmin());
        if ("REMOTE".equals(bot.getRuntimeType())) {
            return ApiResponse.success(agentClient.fetchStatus(bot));
        }
        if (bot.getId() != null && bot.getId() == 1L) {
            com.qqai.capability.im.ImLoginInfo info = imGateway.loginInfo();
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("loggedIn", info != null);
            if (info != null) {
                data.put("qq", String.valueOf(info.qq()));
                data.put("nickname", info.nickname());
            }
            return ApiResponse.success(data);
        }
        throw new BizException(409, "本机多实例尚未支持，请使用 REMOTE agent");
    }

    @PostMapping("/{id}/agent-token/rotate")
    public ApiResponse<Map<String, String>> rotateAgentToken(@PathVariable Long id) {
        Long userId = securityHelper.requireCurrentUserId();
        String token = botService.rotateAgentToken(id, userId, securityHelper.isAdmin(),
                securityHelper.getCurrentUsername());
        return ApiResponse.success(Map.of("agentToken", token));
    }

    public record CreateBotRequest(@NotBlank @Size(max = 64) String name,
                                   String runtimeType,
                                   String onebotUrl,
                                   String webuiUrl) {
    }

    public record RuntimeRequest(String runtimeType, String onebotUrl, String webuiUrl, String agentUrl) {
    }

    public record AgentRegisterRequest(String onebotUrl, String webuiUrl, String agentUrl) {
    }

    public record RenameBotRequest(@NotBlank @Size(max = 64) String name) {
    }
}
