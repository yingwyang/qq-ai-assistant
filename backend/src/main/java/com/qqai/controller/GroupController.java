package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.constant.GroupType;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.Group;
import com.qqai.entity.GroupDigest;
import com.qqai.repository.GroupRepository;
import com.qqai.service.CreditService;
import com.qqai.service.GroupDigestService;
import com.qqai.service.GroupTypeRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 群类型管理 + 群日报 Controller
 * 提供群类型查询、手动设置、AI 自动识别接口；以及 AI 摘要阶段 3 的「群日报」接口
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private static final Logger log = LoggerFactory.getLogger(GroupController.class);

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupTypeRecognitionService recognitionService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private GroupDigestService groupDigestService;

    /**
     * 查询群类型 + 推荐分析类型
     */
    @GetMapping("/{groupId}/type")
    public ResponseEntity<?> getGroupType(@PathVariable String groupId) {
        try {
            List<Group> groups = groupRepository.safeFindByGroupId(groupId);
            if (groups.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "error", "message", "群聊不存在"));
            }
            Group group = groups.get(0);
            GroupType gt = GroupType.fromString(group.getGroupType());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "ok");
            result.put("groupId", groupId);
            result.put("groupType", gt.name());
            result.put("groupTypeLabel", gt.getLabel());
            result.put("groupTypeIcon", gt.getIcon());
            result.put("recommendedAnalysisTypes", gt.getRecommendedAnalysisTypes());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询群类型失败 groupId={}: {}", groupId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 手动设置群类型
     */
    @PutMapping("/{groupId}/type")
    public ResponseEntity<?> setGroupType(@PathVariable String groupId,
                                          @RequestBody Map<String, String> request) {
        try {
            String groupType = request.get("groupType");
            if (!GroupType.isValid(groupType) && !"OTHER".equalsIgnoreCase(groupType)) {
                return ResponseEntity.ok(Map.of("status", "error", "message", "无效的群类型: " + groupType));
            }

            List<Group> groups = groupRepository.safeFindByGroupId(groupId);
            if (groups.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "error", "message", "群聊不存在"));
            }

            GroupType gt = GroupType.fromString(groupType);
            for (Group group : groups) {
                group.setGroupType(gt.name());
                groupRepository.save(group);
            }

            log.info("群类型已设置 groupId={} -> {}", groupId, gt.name());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "ok");
            result.put("groupType", gt.name());
            result.put("groupTypeLabel", gt.getLabel());
            result.put("groupTypeIcon", gt.getIcon());
            result.put("recommendedAnalysisTypes", gt.getRecommendedAnalysisTypes());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("设置群类型失败 groupId={}: {}", groupId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * AI 自动识别群类型（消耗积分，识别结果不自动写入，需用户确认）
     */
    @PostMapping("/{groupId}/recognize-type")
    public ResponseEntity<?> recognizeGroupType(@PathVariable String groupId,
                                                @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "未登录"));
            }

            boolean isAdmin = securityHelper.isAdmin();

            // 只做余额校验（不扣费）；真正扣费挪到识别成功之后。
            // 此前是"先扣 1 积分再识别"，LLM 一失败用户就白扣一次。
            if (!isAdmin) {
                int balance = creditService.getBalance(userId).map(b -> b.getBalance()).orElse(0);
                if (balance < 1) {
                    return ResponseEntity.status(402).body(Map.of(
                            "status", "error",
                            "errorCode", "INSUFFICIENT_CREDITS",
                            "message", "积分不足，无法执行群类型识别"));
                }
            }

            GroupTypeRecognitionService.RecognitionResult result =
                    recognitionService.recognizeGroupType(groupId, forceRefresh);

            if (!result.success) {
                // 识别失败不扣积分，失败原因原样返回（前端会弹 error toast）
                log.warn("群类型识别未成功 groupId={}: {}", groupId, result.reason);
                return ResponseEntity.ok(Map.of(
                        "status", "error",
                        "errorCode", "RECOGNITION_FAILED",
                        "message", "AI 识别失败：" + result.reason));
            }

            int groupCost = 0;
            try {
                CreditService.CreditCostResult groupResult = creditService.spendForGroupType(userId, groupId, isAdmin);
                groupCost = groupResult.getCost();
            } catch (com.qqai.exception.BizException e) {
                log.warn("群类型识别扣费失败(结果已正常返回) userId={}, groupId={}: {}", userId, groupId, e.getMessage());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.putAll(result.toMap());
            response.put("cost", groupCost);
            response.put("message", "识别完成，请确认是否采用此类型");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("群类型识别失败 groupId={}: {}", groupId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ==================== AI 摘要阶段 3：群日报 ====================

    /**
     * 生成（或读取）某群当天日报：{@code POST /api/groups/{groupId}/digest?date=YYYY-MM-DD}
     *
     * <p>{@code date} 可空，为空表示今天。幂等：同群同天已有日报时直接返回已有记录，不再调用大模型。</p>
     *
     * <p>失败语义：参数/无消息等业务异常由 {@code GroupDigestService} 抛 {@code BizException}，
     * 经 {@code GlobalExceptionHandler} 统一转成 {@code ApiResponse}（400 该群当天没有可摘要的消息 /
     * 503 AI 服务不可用）。</p>
     */
    @PostMapping("/{groupId}/digest")
    public ResponseEntity<ApiResponse<?>> generateGroupDigest(
            @PathVariable String groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean force) {

        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }

        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        GroupDigest digest = groupDigestService.generateDailyDigest(groupId, targetDate, force);
        return ResponseEntity.ok(ApiResponse.success(groupDigestService.toView(digest)));
    }

    /**
     * 最新一期日报：{@code GET /api/groups/{groupId}/digest/latest}
     *
     * <p>该群还没有任何日报时 {@code data} 为 null（不是错误），前端据此显示「今日暂无速览」。</p>
     */
    @GetMapping("/{groupId}/digest/latest")
    public ResponseEntity<ApiResponse<?>> getLatestGroupDigest(@PathVariable String groupId) {
        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }

        Optional<GroupDigest> latest = groupDigestService.findLatest(groupId);
        return ResponseEntity.ok(ApiResponse.success(latest.map(groupDigestService::toView).orElse(null)));
    }

    /**
     * 历史日报列表：{@code GET /api/groups/{groupId}/digests?limit=30}
     *
     * <p>{@code limit} 默认 30，服务层收敛到 1 ~ 200。</p>
     */
    @GetMapping("/{groupId}/digests")
    public ResponseEntity<ApiResponse<?>> getGroupDigests(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "30") int limit) {

        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }

        List<Map<String, Object>> views = groupDigestService.findHistory(groupId, limit)
                .stream()
                .map(groupDigestService::toView)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(views));
    }

    /**
     * 群日报三个接口共用的权限校验，口径与 {@code MessageController.getMessagesByGroupId} 一致：
     * 未登录 → 401；管理员跳过群校验；未绑定 QQ → 403「请先绑定QQ账号」；无群可见性 → 403「无权访问该群聊」。
     *
     * @return 校验通过返回 null；否则返回可直接回给前端的错误响应
     */
    private ResponseEntity<ApiResponse<?>> checkGroupAccess(String groupId) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未登录"));
        }
        // 管理员不受群可见性限制
        if (securityHelper.isAdmin()) {
            return null;
        }
        List<String> userQqList = securityHelper.getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "请先绑定QQ账号"));
        }
        if (!securityHelper.hasGroupAccess(groupId, userQqList)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权访问该群聊"));
        }
        return null;
    }
}
