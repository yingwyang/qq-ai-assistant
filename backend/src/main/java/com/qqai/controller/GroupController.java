package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.constant.GroupType;
import com.qqai.entity.Group;
import com.qqai.repository.GroupRepository;
import com.qqai.service.CreditService;
import com.qqai.service.GroupTypeRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 群类型管理 Controller
 * 提供群类型查询、手动设置、AI 自动识别接口
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
}
