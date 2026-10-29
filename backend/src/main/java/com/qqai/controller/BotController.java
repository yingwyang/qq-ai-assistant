package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.BotView;
import com.qqai.entity.Bot;
import com.qqai.dto.common.ApiResponse;
import com.qqai.service.BotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 用户自己的机器人：创建、查看、改名、删除。启停/扫码在后续阶段接入。 */
@RestController
@RequestMapping("/api/bots")
public class BotController {

    private final BotService botService;
    private final SecurityHelper securityHelper;

    public BotController(BotService botService, SecurityHelper securityHelper) {
        this.botService = botService;
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
        Bot bot = botService.create(userId, request.name(), securityHelper.getCurrentUsername());
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

    public record CreateBotRequest(@NotBlank @Size(max = 64) String name) {
    }

    public record RenameBotRequest(@NotBlank @Size(max = 64) String name) {
    }
}
